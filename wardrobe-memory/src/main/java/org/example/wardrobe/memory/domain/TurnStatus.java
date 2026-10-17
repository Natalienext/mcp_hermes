package org.example.wardrobe.memory.domain;

import java.util.Locale;

public enum TurnStatus {
    OPEN, PENDING, DONE, FAILED, ABANDONED;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TurnStatus fromValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
