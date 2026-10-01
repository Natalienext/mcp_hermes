package org.example.wardrobe.infrastructure.persistence;

import java.util.List;
import java.util.Set;

import org.example.wardrobe.domain.Item;
import org.example.wardrobe.domain.ItemExclusionFilter;
import org.example.wardrobe.domain.ItemRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaItemRepository implements ItemRepository {

    private final ItemJpaRepository jpaRepository;

    JpaItemRepository(ItemJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Item> findAll(ItemExclusionFilter filter) {
        return jpaRepository.findAll(ItemSpecifications.matching(filter), Sort.by("id"))
                .stream()
                .map(JpaItemRepository::toDomain)
                .toList();
    }

    private static Item toDomain(ItemEntity entity) {
        return new Item(entity.getId(), entity.getDescription(), entity.getType(),
                Set.copyOf(entity.getColors()), entity.getSeason());
    }
}
