package org.example.wardrobe.infrastructure.persistence;

import java.util.Collection;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.example.wardrobe.domain.Color;
import org.example.wardrobe.domain.ItemExclusionFilter;
import org.springframework.data.jpa.domain.Specification;

final class ItemSpecifications {

    private ItemSpecifications() {
    }

    static Specification<ItemEntity> matching(ItemExclusionFilter filter) {
        return Specification.allOf(
                notIn("type", filter.excludeTypes()),
                notIn("season", filter.excludeSeasons()),
                noColorIn(filter.excludeColors()));
    }

    private static Specification<ItemEntity> notIn(String attribute, Collection<?> values) {
        return (root, query, cb) -> values.isEmpty() ? null : cb.not(root.get(attribute).in(values));
    }

    /**
     * id NOT IN (SELECT id FROM item JOIN item_color WHERE color IN (...)):
     * вещь исключается, если хотя бы один её цвет в списке.
     */
    private static Specification<ItemEntity> noColorIn(Collection<Color> colors) {
        return (root, query, cb) -> {
            if (colors.isEmpty()) {
                return null;
            }
            Subquery<Long> withExcludedColor = query.subquery(Long.class);
            Root<ItemEntity> item = withExcludedColor.from(ItemEntity.class);
            Join<ItemEntity, Color> color = item.join("colors");
            withExcludedColor.select(item.get("id")).where(color.in(colors));
            return cb.not(root.get("id").in(withExcludedColor));
        };
    }
}
