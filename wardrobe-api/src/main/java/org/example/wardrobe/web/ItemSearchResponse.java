package org.example.wardrobe.web;

import java.util.List;

import org.example.wardrobe.domain.Item;

/**
 * Минимальный ответ для агента: только id и описание.
 */
public record ItemSearchResponse(List<ItemView> items) {

    public record ItemView(Long id, String description) {
    }

    static ItemSearchResponse from(List<Item> items) {
        return new ItemSearchResponse(items.stream()
                .map(item -> new ItemView(item.id(), item.description()))
                .toList());
    }
}
