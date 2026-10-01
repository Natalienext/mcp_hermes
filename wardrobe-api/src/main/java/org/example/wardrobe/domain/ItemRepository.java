package org.example.wardrobe.domain;

import java.util.List;

public interface ItemRepository {

    List<Item> findAll(ItemExclusionFilter filter);
}
