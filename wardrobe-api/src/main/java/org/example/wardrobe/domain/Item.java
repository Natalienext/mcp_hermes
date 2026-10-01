package org.example.wardrobe.domain;

import java.util.Set;

public record Item(Long id, String description, ItemType type, Set<Color> colors, Season season) {
}
