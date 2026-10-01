package org.example.wardrobe.web;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.example.wardrobe.application.SearchItemsUseCase;
import org.example.wardrobe.domain.Color;
import org.example.wardrobe.domain.ItemExclusionFilter;
import org.example.wardrobe.domain.ItemType;
import org.example.wardrobe.domain.Season;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final SearchItemsUseCase searchItems;

    public ItemController(SearchItemsUseCase searchItems) {
        this.searchItems = searchItems;
    }

    /**
     * Пример: GET /api/items?excludeType=SKIRT&excludeColor=RED,PINK&excludeSeason=SUMMER
     */
    @GetMapping
    public ItemSearchResponse search(
            @RequestParam(required = false) List<ItemType> excludeType,
            @RequestParam(required = false) List<Color> excludeColor,
            @RequestParam(required = false) List<Season> excludeSeason) {
        ItemExclusionFilter filter = new ItemExclusionFilter(
                toSet(excludeType), toSet(excludeColor), toSet(excludeSeason));
        return ItemSearchResponse.from(searchItems.search(filter));
    }

    /** Пустые значения ("excludeType=") конвертер превращает в null — отбрасываем их. */
    private static <T> Set<T> toSet(List<T> values) {
        Set<T> result = new HashSet<>();
        if (values != null) {
            values.stream().filter(Objects::nonNull).forEach(result::add);
        }
        return result;
    }
}
