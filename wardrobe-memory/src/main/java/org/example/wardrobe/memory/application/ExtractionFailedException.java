package org.example.wardrobe.memory.application;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.MemoryException;

/** Экстракция хода не удалась целиком: {@code MEM-EXT-001} (вызов) или {@code MEM-EXT-002} (ответ вне схемы). */
public class ExtractionFailedException extends MemoryException {

    public ExtractionFailedException(ErrorCode code, String message) {
        super(code, message);
    }
}
