package org.example.wardrobe.memory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class QuoteMatcherTest {

    @Test
    void ignoresCaseYoAndPunctuation() {
        String text = "Слушай, я НЕ ношу юбки — совсем! И зелёный не люблю.";

        assertThat(QuoteMatcher.contains(text, "я не ношу юбки")).isTrue();
        assertThat(QuoteMatcher.contains(text, "Я не ношу юбки, совсем")).isTrue();
        assertThat(QuoteMatcher.contains(text, "зеленый не люблю")).isTrue();
    }

    @Test
    void rejectsParaphraseAndEmptyQuote() {
        String text = "я не ношу юбки";

        assertThat(QuoteMatcher.contains(text, "не люблю юбки")).isFalse();
        assertThat(QuoteMatcher.contains(text, "юбки не ношу")).isFalse();
        assertThat(QuoteMatcher.contains(text, " ,!")).isFalse();
        assertThat(QuoteMatcher.contains(text, null)).isFalse();
        assertThat(QuoteMatcher.contains("", "юбки")).isFalse();
    }

    @Test
    void assistantOnly() {
        Turn turn = Turn.open("t-001", "ext-1", "me", "s-1", "Подбери образ на работу",
                Instant.parse("2026-10-17T10:00:00Z")).close("Учту, что вы не носите юбки");

        assertThat(QuoteMatcher.contains(turn.assistantText(), "не носите юбки")).isTrue();
        assertThat(QuoteMatcher.contains(turn.userText(), "не носите юбки")).isFalse();
    }
}
