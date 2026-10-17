package org.example.wardrobe.memory.application;

/** Очередь разбора закрытых ходов (D2): {@code submit} возвращается сразу, разбор — {@link ExtractTurnUseCase}. */
public interface ExtractionQueue {

    void submit(String turnId);
}
