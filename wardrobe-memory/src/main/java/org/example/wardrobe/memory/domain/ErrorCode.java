package org.example.wardrobe.memory.domain;

import static org.example.wardrobe.memory.domain.ErrorCode.Category.CONFLICT;
import static org.example.wardrobe.memory.domain.ErrorCode.Category.EXTRACTOR;
import static org.example.wardrobe.memory.domain.ErrorCode.Category.INTERNAL;
import static org.example.wardrobe.memory.domain.ErrorCode.Category.NOT_FOUND;
import static org.example.wardrobe.memory.domain.ErrorCode.Category.VALIDATION;

public enum ErrorCode {
    VAL_001(VALIDATION), VAL_002(VALIDATION), VAL_003(VALIDATION), VAL_004(VALIDATION), VAL_005(VALIDATION),
    VAL_006(VALIDATION), VAL_007(VALIDATION), VAL_008(VALIDATION), VAL_009(VALIDATION), VAL_010(VALIDATION),
    VAL_011(VALIDATION), VAL_012(VALIDATION), VAL_013(VALIDATION), VAL_015(VALIDATION),
    NF_001(NOT_FOUND), NF_002(NOT_FOUND),
    STATE_001(CONFLICT), STATE_002(CONFLICT), STATE_003(CONFLICT), STATE_004(CONFLICT), STATE_005(CONFLICT),
    STATE_006(CONFLICT), STATE_007(CONFLICT), STATE_008(CONFLICT), STATE_010(CONFLICT),
    EXT_001(EXTRACTOR), EXT_002(EXTRACTOR),
    INT_001(INTERNAL);

    public enum Category { VALIDATION, NOT_FOUND, CONFLICT, EXTRACTOR, INTERNAL }

    private final Category category;

    ErrorCode(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }

    /** Код в ответе API: {@code MEM-VAL-001}. */
    public String code() {
        return "MEM-" + name().replace('_', '-');
    }
}
