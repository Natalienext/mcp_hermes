package org.example.wardrobe.memory.web;

import java.time.Instant;

import org.example.wardrobe.memory.domain.Fact;

/** Факт в ответе API; {@code standing} — пометка «догадка модели» для {@code memory_list} (AC-9). */
public record FactView(
        String id,
        String kind,
        String scope,
        String fact,
        String quote,
        String standing,
        String status,
        String createdAt,
        String supersedes,
        String sourceSessionId,
        String forgottenAt,
        String forgottenIn) {

    static FactView from(Fact fact) {
        return new FactView(fact.id(), fact.kind().value(), fact.scope(), fact.text(), fact.quote(),
                fact.standing().value(), fact.status().value(), fact.createdAt().toString(), fact.supersedes(),
                fact.sourceSessionId(), text(fact.forgottenAt()), fact.forgottenIn());
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
