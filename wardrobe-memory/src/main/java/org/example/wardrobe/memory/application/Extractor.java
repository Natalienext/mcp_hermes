package org.example.wardrobe.memory.application;

import java.util.List;

/**
 * Модель, извлекающая факты из хода. Одна попытка (Р12); сбой вызова или ответ вне схемы — {@link ExtractionFailedException}.
 */
public interface Extractor {

    List<ExtractorDecision> extract(ExtractionInput input);
}
