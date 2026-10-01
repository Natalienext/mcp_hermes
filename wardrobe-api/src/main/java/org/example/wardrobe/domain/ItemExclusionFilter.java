package org.example.wardrobe.domain;

import java.util.Set;

/**
 * Жёсткие исключающие фильтры. Пустое множество — фильтр не применяется.
 * excludeColors исключает вещь, если хотя бы один её цвет попал в список.
 */
public record ItemExclusionFilter(Set<ItemType> excludeTypes, Set<Color> excludeColors, Set<Season> excludeSeasons) {

    public ItemExclusionFilter {
        excludeTypes = excludeTypes == null ? Set.of() : Set.copyOf(excludeTypes);
        excludeColors = excludeColors == null ? Set.of() : Set.copyOf(excludeColors);
        excludeSeasons = excludeSeasons == null ? Set.of() : Set.copyOf(excludeSeasons);
    }
}
