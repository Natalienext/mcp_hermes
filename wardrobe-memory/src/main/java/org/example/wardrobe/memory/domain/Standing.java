package org.example.wardrobe.memory.domain;

import java.util.Arrays;
import java.util.Locale;

public enum Standing {
    STATED, INFERRED;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Значение из запроса или ответа экстрактора; не из списка — {@code MEM-VAL-007}. */
    public static Standing parse(String value) {
        return Arrays.stream(values())
                .filter(standing -> standing.value().equals(value))
                .findFirst()
                .orElseThrow(() -> new MemoryException(ErrorCode.VAL_007,
                        "invalid standing \"" + value + "\", valid: stated, inferred"));
    }
}
