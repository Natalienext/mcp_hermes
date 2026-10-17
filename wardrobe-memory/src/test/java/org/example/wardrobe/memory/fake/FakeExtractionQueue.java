package org.example.wardrobe.memory.fake;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.example.wardrobe.memory.application.ExtractionQueue;

/** Синхронная очередь: запоминает id и сразу передаёт их обработчику, если он задан. */
public class FakeExtractionQueue implements ExtractionQueue {

    public final List<String> submitted = new ArrayList<>();
    public Consumer<String> handler = id -> { };

    @Override
    public void submit(String turnId) {
        submitted.add(turnId);
        handler.accept(turnId);
    }
}
