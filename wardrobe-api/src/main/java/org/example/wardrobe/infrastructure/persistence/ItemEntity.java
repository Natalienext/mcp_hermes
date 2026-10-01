package org.example.wardrobe.infrastructure.persistence;

import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.example.wardrobe.domain.Color;
import org.example.wardrobe.domain.ItemType;
import org.example.wardrobe.domain.Season;

@Entity
@Table(name = "item")
public class ItemEntity {

    @Id
    private Long id;

    private String description;

    @Enumerated(EnumType.STRING)
    private ItemType type;

    @ElementCollection
    @CollectionTable(name = "item_color", joinColumns = @JoinColumn(name = "item_id"))
    @Column(name = "color")
    @Enumerated(EnumType.STRING)
    private Set<Color> colors;

    @Enumerated(EnumType.STRING)
    private Season season;

    protected ItemEntity() {
    }

    public Long getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public ItemType getType() {
        return type;
    }

    public Set<Color> getColors() {
        return colors;
    }

    public Season getSeason() {
        return season;
    }
}
