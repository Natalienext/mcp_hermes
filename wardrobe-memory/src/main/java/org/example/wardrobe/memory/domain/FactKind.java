package org.example.wardrobe.memory.domain;

import java.util.Arrays;
import java.util.Locale;

public enum FactKind {
    PREFERENCE, CONSTRAINT, BACKGROUND;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Значение из запроса или ответа экстрактора; не из списка — {@code MEM-VAL-002}. */
    public static FactKind parse(String value) {
        return Arrays.stream(values())
                .filter(kind -> kind.value().equals(value))
                .findFirst()
                .orElseThrow(() -> new MemoryException(ErrorCode.VAL_002,
                        "invalid kind \"" + value + "\", valid: preference, constraint, background"));
    }
}
