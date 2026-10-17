package org.example.wardrobe.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.wardrobe.memory.fake.Fixtures.assertCode;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactKind;
import org.example.wardrobe.memory.domain.FactStatus;
import org.example.wardrobe.memory.domain.Standing;
import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.fake.FakeFactRepository;
import org.example.wardrobe.memory.fake.FakeTurnRepository;
import org.example.wardrobe.memory.fake.Fixtures;
import org.junit.jupiter.api.Test;

class RememberUseCaseTest {

    private final FakeTurnRepository turns = new FakeTurnRepository();
    private final FakeFactRepository facts = new FakeFactRepository();
    private final RememberUseCase useCase =
            new RememberUseCase(turns, new FactWriter(facts, Fixtures.tx(), Fixtures.CLOCK));

    @Test
    void remembersStatedFactFromOpenTurn() {
        Turn turn = turns.open("ext-1", "me", "Запомни: я не ношу юбки");

        Fact fact = useCase.remember("me", "constraint", "skirts", "Не носит юбки", "я не ношу юбки", null);

        assertThat(fact.kind()).isEqualTo(FactKind.CONSTRAINT);
        assertThat(fact.standing()).isEqualTo(Standing.STATED);
        assertThat(fact.sourceTurnId()).isEqualTo(turn.id());
        assertThat(fact.sourceSessionId()).isEqualTo(turn.sessionId());
    }

    @Test
    void noOpenTurn() {
        turns.update(turns.open("ext-1", "me", "я не ношу юбки").abandon());

        assertCode(ErrorCode.STATE_006,
                () -> useCase.remember("me", "constraint", "skirts", "Не носит юбки", "я не ношу юбки", null));
        assertThat(facts.facts).isEmpty();
    }

    @Test
    void quoteNotInTurn() {
        turns.open("ext-1", "me", "Запомни, что я не люблю юбки");

        assertCode(ErrorCode.VAL_006,
                () -> useCase.remember("me", "constraint", "skirts", "Не носит юбки", "я не ношу юбки", null));
        assertThat(facts.facts).isEmpty();
    }

    @Test
    void supersedesExistingFact() {
        turns.update(turns.open("ext-1", "me", "я не ношу юбки").abandon());
        Fact old = Fact.create(facts.nextId(), turns.findById("t-001").orElseThrow(), FactKind.CONSTRAINT, "skirts",
                "Не носит юбки", "я не ношу юбки", Standing.INFERRED, null, Fixtures.NOW);
        facts.insert(old);
        turns.open("ext-2", "me", "Запомни: теперь я ношу длинные юбки");

        Fact next = useCase.remember("me", "preference", "skirts", "Носит длинные юбки", "я ношу длинные юбки",
                old.id());

        assertThat(next.supersedes()).isEqualTo(old.id());
        assertThat(facts.facts.get(old.id()).status()).isEqualTo(FactStatus.SUPERSEDED);
    }

    @Test
    void supersedeErrors() {
        turns.open("ext-1", "me", "Запомни: я ношу длинные юбки");

        assertCode(ErrorCode.NF_001,
                () -> useCase.remember("me", "constraint", "skirts", "Носит длинные юбки", "я ношу длинные юбки", "f-404"));
        assertCode(ErrorCode.VAL_009,
                () -> useCase.remember("me", "constraint", "skirts", "Носит длинные юбки", "я ношу длинные юбки", "404"));
    }

    @Test
    void otherUsersFactIsState002() {
        Turn foreign = turns.open("ext-1", "other", "я не ношу юбки");
        Fact theirs = Fact.create(facts.nextId(), foreign, FactKind.CONSTRAINT, "skirts", "Не носит юбки",
                "я не ношу юбки", Standing.STATED, null, Fixtures.NOW);
        facts.insert(theirs);
        turns.open("ext-2", "me", "Запомни: я ношу длинные юбки");

        assertCode(ErrorCode.STATE_002, () -> useCase.remember("me", "constraint", "skirts", "Носит длинные юбки",
                "я ношу длинные юбки", theirs.id()));
        assertThat(facts.facts.get(theirs.id()).status()).isEqualTo(FactStatus.ACTIVE);
    }

    @Test
    void invalidUserIsVal001() {
        assertCode(ErrorCode.VAL_001,
                () -> useCase.remember("кто-то", "constraint", "skirts", "Не носит юбки", "я не ношу юбки", null));
    }
}
