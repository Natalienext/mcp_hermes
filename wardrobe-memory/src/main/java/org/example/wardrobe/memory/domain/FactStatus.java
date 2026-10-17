package org.example.wardrobe.memory.domain;

import java.util.Locale;

public enum FactStatus {
    ACTIVE, SUPERSEDED, FORGOTTEN;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static FactStatus fromValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
