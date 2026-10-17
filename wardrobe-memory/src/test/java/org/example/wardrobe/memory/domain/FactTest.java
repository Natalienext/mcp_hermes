package org.example.wardrobe.memory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FactTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final Turn TURN = Turn.open("t-001", "ext-1", "me", "s-1",
            "Я не ношу юбки, а ещё люблю синий", NOW);

    private static Fact skirts(String id, Standing standing) {
        return Fact.create(id, TURN, FactKind.CONSTRAINT, "skirts", "Не носит юбки", "я не ношу юбки", standing,
                null, NOW);
    }

    private static Fact replacement(String id, String target, String scope, Standing standing) {
        Turn turn = Turn.open("t-002", "ext-2", "me", "s-2", "теперь иногда ношу юбки", NOW);
        return Fact.create(id, turn, FactKind.CONSTRAINT, scope, "Иногда носит юбки", "иногда ношу юбки", standing,
                target, NOW);
    }

    @Test
    void createTakesSourceFromTurn() {
        Fact fact = skirts("f-001", Standing.STATED);

        assertThat(fact.userId()).isEqualTo("me");
        assertThat(fact.sourceTurnId()).isEqualTo("t-001");
        assertThat(fact.sourceSessionId()).isEqualTo("s-1");
        assertThat(fact.status()).isEqualTo(FactStatus.ACTIVE);
        assertThat(fact.supersedes()).isNull();
        assertThat(fact.recallable()).isTrue();
    }

    @Test
    void quoteMustBeInUserText() {
        assertCode(ErrorCode.VAL_006, () -> Fact.create("f-001", TURN, FactKind.CONSTRAINT, "skirts",
                "Не носит юбки", "не люблю юбки", Standing.STATED, null, NOW));
    }

    @Test
    void quoteFromAssistantTextIsRejected() {
        Turn pending = Turn.open("t-003", "ext-3", "me", "s-1", "Подбери образ", NOW).close("Вы не носите юбки");

        assertCode(ErrorCode.VAL_006, () -> Fact.create("f-001", pending, FactKind.CONSTRAINT, "skirts",
                "Не носит юбки", "не носите юбки", Standing.STATED, null, NOW));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Skirts", "skirts_long", "-skirts", "skirts-", "skirts--x", "юбки", ""})
    void scopeMustBeKey(String scope) {
        assertCode(ErrorCode.VAL_003, () -> Fact.create("f-001", TURN, FactKind.CONSTRAINT, scope,
                "Не носит юбки", "я не ношу юбки", Standing.STATED, null, NOW));
    }

    @Test
    void scopeLength() {
        String max = "a".repeat(48);
        assertThat(Fact.create("f-001", TURN, FactKind.CONSTRAINT, max, "Не носит юбки", "я не ношу юбки",
                Standing.STATED, null, NOW).scope()).isEqualTo(max);
        assertCode(ErrorCode.VAL_003, () -> Fact.create("f-001", TURN, FactKind.CONSTRAINT, max + "a",
                "Не носит юбки", "я не ношу юбки", Standing.STATED, null, NOW));
    }

    @Test
    void fieldFormats() {
        assertCode(ErrorCode.VAL_009, () -> Fact.create("f-1", TURN, FactKind.CONSTRAINT, "skirts",
                "Не носит юбки", "я не ношу юбки", Standing.STATED, null, NOW));
        assertCode(ErrorCode.VAL_009, () -> Fact.create("f-002", TURN, FactKind.CONSTRAINT, "skirts",
                "Не носит юбки", "я не ношу юбки", Standing.STATED, "skirts", NOW));
        assertCode(ErrorCode.VAL_002, () -> Fact.create("f-001", TURN, null, "skirts",
                "Не носит юбки", "я не ношу юбки", Standing.STATED, null, NOW));
        assertCode(ErrorCode.VAL_007, () -> Fact.create("f-001", TURN, FactKind.CONSTRAINT, "skirts",
                "Не носит юбки", "я не ношу юбки", null, null, NOW));
        assertCode(ErrorCode.VAL_004, () -> Fact.create("f-001", TURN, FactKind.CONSTRAINT, "skirts",
                " ", "я не ношу юбки", Standing.STATED, null, NOW));
        assertCode(ErrorCode.VAL_004, () -> Fact.create("f-001", TURN, FactKind.CONSTRAINT, "skirts",
                "я".repeat(201), "я не ношу юбки", Standing.STATED, null, NOW));
        assertCode(ErrorCode.VAL_005, () -> Fact.create("f-001", TURN, FactKind.CONSTRAINT, "skirts",
                "Не носит юбки", "", Standing.STATED, null, NOW));
    }

    @Test
    void parseKindAndStanding() {
        assertThat(FactKind.parse("background")).isEqualTo(FactKind.BACKGROUND);
        assertThat(Standing.parse("inferred")).isEqualTo(Standing.INFERRED);
        assertCode(ErrorCode.VAL_002, () -> FactKind.parse("CONSTRAINT"));
        assertCode(ErrorCode.VAL_007, () -> Standing.parse("guess"));
    }

    @Test
    void scopeTakenByAnotherActiveFact() {
        Fact existing = skirts("f-001", Standing.STATED);
        Fact next = skirts("f-002", Standing.INFERRED);

        next.checkScopeFree(Optional.empty());
        existing.checkScopeFree(Optional.of(existing));
        assertThatThrownBy(() -> next.checkScopeFree(Optional.of(existing)))
                .isInstanceOfSatisfying(MemoryException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.STATE_004))
                .hasMessage("scope \"skirts\" already has active fact f-001");
    }

    @Test
    void supersede() {
        Fact old = skirts("f-001", Standing.STATED);
        Fact next = replacement("f-002", "f-001", "skirts", Standing.STATED);

        Fact superseded = old.supersededBy(next);

        assertThat(superseded.status()).isEqualTo(FactStatus.SUPERSEDED);
        assertThat(superseded.text()).isEqualTo(old.text());
        assertThat(superseded.recallable()).isFalse();
        assertThat(next.supersedes()).isEqualTo("f-001");
    }

    @Test
    void supersedeRejected() {
        Fact old = skirts("f-001", Standing.STATED);
        Turn other = Turn.open("t-009", "ext-9", "someone", "s-9", "иногда ношу юбки", NOW);
        Fact foreign = Fact.create("f-002", other, FactKind.CONSTRAINT, "skirts", "Иногда носит юбки",
                "иногда ношу юбки", Standing.STATED, "f-001", NOW);

        assertCode(ErrorCode.STATE_002, () -> old.supersededBy(foreign));
        Fact superseded = old.supersededBy(replacement("f-002", "f-001", "skirts", Standing.STATED));
        assertCode(ErrorCode.STATE_001,
                () -> superseded.supersededBy(replacement("f-003", "f-001", "skirts", Standing.STATED)));
        assertCode(ErrorCode.STATE_003,
                () -> old.supersededBy(replacement("f-002", "f-001", "skirts-short", Standing.STATED)));
        assertCode(ErrorCode.STATE_005,
                () -> old.supersededBy(replacement("f-002", "f-001", "skirts", Standing.INFERRED)));
    }

    @Test
    void inferredMayReplaceInferredAndStatedReplacesInferred() {
        Fact guess = skirts("f-001", Standing.INFERRED);

        assertThat(guess.supersededBy(replacement("f-002", "f-001", "skirts", Standing.INFERRED)).status())
                .isEqualTo(FactStatus.SUPERSEDED);
        assertThat(guess.supersededBy(replacement("f-002", "f-001", "skirts", Standing.STATED)).status())
                .isEqualTo(FactStatus.SUPERSEDED);
    }

    @Test
    void forgetErasesTextAndKeepsTrace() {
        Instant later = NOW.plusSeconds(3600);
        Fact forgotten = skirts("f-001", Standing.STATED).forget("me", "s-7", later);

        assertThat(forgotten.status()).isEqualTo(FactStatus.FORGOTTEN);
        assertThat(forgotten.text()).isEmpty();
        assertThat(forgotten.quote()).isEmpty();
        assertThat(forgotten.forgottenAt()).isEqualTo(later);
        assertThat(forgotten.forgottenIn()).isEqualTo("s-7");
        assertThat(forgotten.id()).isEqualTo("f-001");
        assertThat(forgotten.scope()).isEqualTo("skirts");
        assertThat(forgotten.kind()).isEqualTo(FactKind.CONSTRAINT);
        assertThat(forgotten.recallable()).isFalse();
    }

    @Test
    void forgetRejected() {
        Fact fact = skirts("f-001", Standing.STATED);

        assertCode(ErrorCode.STATE_002, () -> fact.forget("someone", "s-7", NOW));
        Fact forgotten = fact.forget("me", "s-7", NOW);
        assertCode(ErrorCode.STATE_001, () -> forgotten.forget("me", "s-8", NOW));
        Fact superseded = fact.supersededBy(replacement("f-002", "f-001", "skirts", Standing.STATED));
        assertCode(ErrorCode.STATE_001, () -> superseded.forget("me", "s-7", NOW));
    }

    @Test
    void eraseSupersededKeepsStatus() {
        Fact superseded = skirts("f-001", Standing.STATED)
                .supersededBy(replacement("f-002", "f-001", "skirts", Standing.STATED));
        Instant later = NOW.plusSeconds(60);

        Fact erased = superseded.erase(later);

        assertThat(erased.status()).isEqualTo(FactStatus.SUPERSEDED);
        assertThat(erased.text()).isEmpty();
        assertThat(erased.quote()).isEmpty();
        assertThat(erased.forgottenAt()).isEqualTo(later);
        assertThat(erased.erase(later.plusSeconds(60))).isEqualTo(erased);
        assertThatThrownBy(() -> skirts("f-003", Standing.STATED).erase(later))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void assertCode(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(MemoryException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
