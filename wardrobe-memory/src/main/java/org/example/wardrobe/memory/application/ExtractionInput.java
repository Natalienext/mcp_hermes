package org.example.wardrobe.memory.application;

import java.util.List;

import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.Turn;

/** Вход экстрактора: реплика, ответ и все действующие факты пользователя (D7). */
public record ExtractionInput(String userText, String assistantText, List<Candidate> candidates) {

    public record Candidate(String id, String kind, String scope, String fact, String standing) {
    }

    public static ExtractionInput of(Turn turn, List<Fact> active) {
        List<Candidate> candidates = active.stream()
                .map(fact -> new Candidate(fact.id(), fact.kind().value(), fact.scope(), fact.text(),
                        fact.standing().value()))
                .toList();
        return new ExtractionInput(turn.userText(), turn.assistantText(), candidates);
    }
}
