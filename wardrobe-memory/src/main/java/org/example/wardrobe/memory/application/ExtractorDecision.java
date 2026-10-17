package org.example.wardrobe.memory.application;

/**
 * Решение модели по одному сведению, все поля — строки как в ответе; разбор и проверки — в {@code FactWriter}.
 */
public record ExtractorDecision(
        String op,
        String targetId,
        String kind,
        String scope,
        String fact,
        String quote,
        String standing) {
}
