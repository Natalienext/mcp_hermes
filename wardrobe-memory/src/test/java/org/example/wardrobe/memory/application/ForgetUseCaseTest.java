package org.example.wardrobe.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.wardrobe.memory.fake.Fixtures.add;
import static org.example.wardrobe.memory.fake.Fixtures.assertCode;
import static org.example.wardrobe.memory.fake.Fixtures.supersede;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactStatus;
import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.fake.FakeFactRepository;
import org.example.wardrobe.memory.fake.FakeFactSearch;
import org.example.wardrobe.memory.fake.FakeTurnRepository;
import org.example.wardrobe.memory.fake.Fixtures;
import org.junit.jupiter.api.Test;

class ForgetUseCaseTest {

    private final FakeTurnRepository turns = new FakeTurnRepository();
    private final FakeFactRepository facts = new FakeFactRepository();
    private final FakeFactSearch search = new FakeFactSearch();
    private final FactWriter writer = new FactWriter(facts, Fixtures.tx(), Fixtures.CLOCK);
    private final ForgetUseCase useCase = new ForgetUseCase(turns, facts, search, Fixtures.tx(), Fixtures.CLOCK);

    private Fact skirtsChain() {
        Turn first = turns.open("ext-1", "me", "я не ношу юбки");
        Fact old = writer.apply(first, add("skirts", "Не носит юбки", "я не ношу юбки", "stated")).orElseThrow();
        turns.update(first.abandon());
        Turn second = turns.open("ext-2", "me", "теперь ношу длинные юбки");
        Fact head = writer.apply(second, supersede(old.id(), "skirts", "Носит длинные юбки", "ношу длинные юбки"))
                .orElseThrow();
        turns.update(second.abandon());
        return head;
    }

    @Test
    void forgetsChainAndErasesSourceTurns() {
        Fact head = skirtsChain();
        Turn current = turns.open("ext-3", "me", "Забудь, что я ношу юбки");

        Fact forgotten = useCase.forget("me", head.id());

        assertThat(forgotten.status()).isEqualTo(FactStatus.FORGOTTEN);
        assertThat(forgotten.forgottenAt()).isEqualTo(Fixtures.NOW);
        assertThat(forgotten.forgottenIn()).isEqualTo(current.sessionId());
        assertThat(facts.facts.get("f-002")).isEqualTo(forgotten);
        Fact older = facts.facts.get("f-001");
        assertThat(older.status()).isEqualTo(FactStatus.SUPERSEDED);
        assertThat(older.text()).isEmpty();
        assertThat(older.quote()).isEmpty();
        assertThat(turns.findById("t-001").orElseThrow().userText()).isEmpty();
        assertThat(turns.findById("t-002").orElseThrow().userText()).isEmpty();
        Turn after = turns.findById(current.id()).orElseThrow();
        assertThat(after.forgetRequested()).isTrue();
        assertThat(after.userText()).isEqualTo("Забудь, что я ношу юбки");
        assertThat(search.compactions).isEqualTo(1);
    }

    @Test
    void forgetInSourceTurnErasesAndMarksIt() {
        Turn turn = turns.open("ext-1", "me", "Запомни, что я не ношу юбки. Хотя нет, забудь");
        Fact fact = writer.apply(turn, add("skirts", "Не носит юбки", "я не ношу юбки", "stated")).orElseThrow();

        useCase.forget("me", fact.id());

        Turn after = turns.findById(turn.id()).orElseThrow();
        assertThat(after.userText()).isEmpty();
        assertThat(after.erasedAt()).isEqualTo(Fixtures.NOW);
        assertThat(after.forgetRequested()).isTrue();
    }

    @Test
    void noOpenTurnIsState006() {
        Fact head = skirtsChain();

        assertCode(ErrorCode.STATE_006, () -> useCase.forget("me", head.id()));
        assertThat(facts.facts.get(head.id()).status()).isEqualTo(FactStatus.ACTIVE);
        assertThat(search.compactions).isZero();
    }

    @Test
    void unknownFactIsNf001() {
        turns.open("ext-1", "me", "забудь f-404");

        assertCode(ErrorCode.NF_001, () -> useCase.forget("me", "f-404"));
        assertThat(search.compactions).isZero();
    }

    @Test
    void badIdIsVal009() {
        turns.open("ext-1", "me", "забудь");

        assertCode(ErrorCode.VAL_009, () -> useCase.forget("me", "skirts"));
    }

    @Test
    void forgottenOrSupersededIsState001() {
        Fact head = skirtsChain();
        turns.open("ext-3", "me", "забудь");
        useCase.forget("me", head.id());

        assertCode(ErrorCode.STATE_001, () -> useCase.forget("me", head.id()));
        assertCode(ErrorCode.STATE_001, () -> useCase.forget("me", "f-001"));
        assertThat(search.compactions).isEqualTo(1);
    }

    @Test
    void otherUsersFactIsState002() {
        Turn foreign = turns.open("ext-1", "other", "я не ношу юбки");
        Fact theirs = writer.apply(foreign, add("skirts", "Не носит юбки", "я не ношу юбки", "stated")).orElseThrow();
        turns.open("ext-2", "me", "забудь");

        assertCode(ErrorCode.STATE_002, () -> useCase.forget("me", theirs.id()));
        assertThat(facts.facts.get(theirs.id()).text()).isEqualTo("Не носит юбки");
    }
}
