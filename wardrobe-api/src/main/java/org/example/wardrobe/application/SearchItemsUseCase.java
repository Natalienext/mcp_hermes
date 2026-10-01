package org.example.wardrobe.application;

import java.util.List;

import org.example.wardrobe.domain.Item;
import org.example.wardrobe.domain.ItemExclusionFilter;
import org.example.wardrobe.domain.ItemRepository;
import org.springframework.stereotype.Service;

@Service
public class SearchItemsUseCase {

    private final ItemRepository itemRepository;

    public SearchItemsUseCase(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    public List<Item> search(ItemExclusionFilter filter) {
        return itemRepository.findAll(filter);
    }
}
