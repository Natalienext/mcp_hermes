package org.example.wardrobe.memory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class TurnTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    private static Turn open() {
        return Turn.open("t-001", "ext-1", "me", "s-1", "я не ношу юбки", NOW);
    }

    @Test
    void openCloseDone() {
        Turn pending = open().close("Хорошо, без юбок");

        assertThat(pending.status()).isEqualTo(TurnStatus.PENDING);
        assertThat(pending.assistantText()).isEqualTo("Хорошо, без юбок");
        assertThat(pending.needsExtraction()).isTrue();

        Turn done = pending.markDone();
        assertThat(done.status()).isEqualTo(TurnStatus.DONE);
        assertThat(done.assistantText()).isEmpty();
        assertThat(done.needsExtraction()).isFalse();
    }

    @Test
    void markFailedKeepsReasonAndErasesAssistantText() {
        Turn failed = open().close("ответ").markFailed("MEM-EXT-001: extractor unavailable: timeout");

        assertThat(failed.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(failed.failReason()).startsWith("MEM-EXT-001");
        assertThat(failed.assistantText()).isEmpty();
    }

    @Test
    void abandonOnlyFromOpen() {
        assertThat(open().abandon().status()).isEqualTo(TurnStatus.ABANDONED);

        assertThatThrownBy(() -> open().abandon().abandon())
                .isInstanceOfSatisfying(MemoryException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.STATE_007))
                .hasMessage("turn t-001 is abandoned");
    }

    @Test
    void invalidTransitionsAreState007() {
        Turn pending = open().close("ответ");

        assertState007(() -> pending.close("ещё"));
        assertState007(pending::abandon);
        assertState007(pending::requestForget);
        assertState007(() -> open().markDone());
        assertState007(() -> open().markFailed("x"));
        assertState007(() -> pending.markDone().markFailed("x"));
    }

    @Test
    void forgetRequestedTurnIsNotExtracted() {
        Turn pending = open().requestForget().close("Забыла");

        assertThat(pending.forgetRequested()).isTrue();
        assertThat(pending.needsExtraction()).isFalse();
    }

    @Test
    void eraseUserTextIsIdempotentInAnyStatus() {
        Instant later = NOW.plusSeconds(60);
        Turn erased = open().close("ответ").markDone().eraseUserText(later);

        assertThat(erased.userText()).isEmpty();
        assertThat(erased.erasedAt()).isEqualTo(later);
        assertThat(erased.eraseUserText(later.plusSeconds(60))).isEqualTo(erased);
        assertThat(open().eraseUserText(later).status()).isEqualTo(TurnStatus.OPEN);
    }

    @Test
    void fieldValidation() {
        assertCode(ErrorCode.VAL_001, () -> Turn.open("t-001", "ext-1", "bad user", "s-1", "текст", NOW));
        assertCode(ErrorCode.VAL_011, () -> Turn.open("t-001", " ", "me", "s-1", "текст", NOW));
        assertCode(ErrorCode.VAL_011, () -> Turn.open("t-001", "ext-1", "me", null, "текст", NOW));
        assertCode(ErrorCode.VAL_008, () -> Turn.open("t-001", "ext-1", "me", "s-1", "", NOW));
        assertCode(ErrorCode.VAL_008, () -> Turn.open("t-001", "ext-1", "me", "s-1", "я".repeat(20_001), NOW));
        assertCode(ErrorCode.VAL_008, () -> open().close("я".repeat(20_001)));
        assertThat(open().close("я".repeat(20_000)).assistantText()).hasSize(20_000);
        assertThatThrownBy(() -> Turn.open("x-1", "ext-1", "me", "s-1", "текст", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertState007(Runnable action) {
        assertCode(ErrorCode.STATE_007, action);
    }

    private static void assertCode(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(MemoryException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
