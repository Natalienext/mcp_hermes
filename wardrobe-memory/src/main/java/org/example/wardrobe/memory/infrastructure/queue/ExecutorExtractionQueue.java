package org.example.wardrobe.memory.infrastructure.queue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import org.example.wardrobe.memory.application.ExtractTurnUseCase;
import org.example.wardrobe.memory.application.ExtractionQueue;
import org.springframework.stereotype.Component;

/** Очередь в памяти процесса (D2): один поток, ходы разбираются по порядку; при рестарте {@code pending} не разбираются. */
@Component
public class ExecutorExtractionQueue implements ExtractionQueue {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "extraction");
        thread.setDaemon(true);
        return thread;
    });
    private final ExtractTurnUseCase extractTurn;

    public ExecutorExtractionQueue(ExtractTurnUseCase extractTurn) {
        this.extractTurn = extractTurn;
    }

    @Override
    public void submit(String turnId) {
        executor.execute(() -> extractTurn.process(turnId));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
