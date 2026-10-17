package org.example.wardrobe.memory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class RecallLineTest {

    private static final Instant CREATED = Instant.parse("2026-10-16T23:30:00Z");

    private static Fact fact(String userText, String quote) {
        Turn turn = Turn.open("t-001", "ext-1", "me", "s-1", userText, CREATED);
        return Fact.create("f-012", turn, FactKind.CONSTRAINT, "skirts", "Не носит юбки", quote, Standing.STATED,
                null, CREATED);
    }

    private static Instant at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC).instant();
    }

    @Test
    void format() {
        assertThat(fact("я не ношу юбки", "я не ношу юбки").recallLine(at("2026-10-17T00:10:00Z")))
                .isEqualTo("f-012 | constraint | вчера | Не носит юбки | «я не ношу юбки»");
    }

    @Test
    void ageByUtcDates() {
        Fact fact = fact("я не ношу юбки", "я не ношу юбки");

        assertThat(fact.recallLine(at("2026-10-16T23:59:59Z"))).contains("| сегодня |");
        assertThat(fact.recallLine(at("2026-10-17T23:59:59Z"))).contains("| вчера |");
        assertThat(fact.recallLine(at("2026-10-21T08:00:00Z"))).contains("| 5 дней назад |");
    }

    @Test
    void longQuoteIsCutByWord() {
        String quote = "я вообще никогда не ношу юбки и платья на работу";
        String line = fact(quote, quote).recallLine(CREATED);

        assertThat(line).endsWith("| «я вообще никогда не ношу юбки и платья…»");
        assertThat("я вообще никогда не ношу юбки и платья").hasSizeLessThanOrEqualTo(40);
    }

    @Test
    void quoteOf40CharsIsKept() {
        String quote = "я".repeat(10) + " " + "б".repeat(29);
        assertThat(quote).hasSize(40);

        assertThat(fact(quote, quote).recallLine(CREATED)).endsWith("«" + quote + "»");
    }

    @Test
    void longWordWithoutSpacesIsCutAt40() {
        String quote = "ю".repeat(45);

        assertThat(fact(quote, quote).recallLine(CREATED)).endsWith("«" + "ю".repeat(40) + "…»");
    }
}
