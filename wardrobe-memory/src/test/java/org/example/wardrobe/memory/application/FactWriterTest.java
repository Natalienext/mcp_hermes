package org.example.wardrobe.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.wardrobe.memory.fake.Fixtures.add;
import static org.example.wardrobe.memory.fake.Fixtures.assertCode;
import static org.example.wardrobe.memory.fake.Fixtures.supersede;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactStatus;
import org.example.wardrobe.memory.domain.Standing;
import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.fake.FakeFactRepository;
import org.example.wardrobe.memory.fake.FakeTurnRepository;
import org.example.wardrobe.memory.fake.Fixtures;
import org.junit.jupiter.api.Test;

class FactWriterTest {

    private final FakeTurnRepository turns = new FakeTurnRepository();
    private final FakeFactRepository facts = new FakeFactRepository();
    private final FactWriter writer = new FactWriter(facts, Fixtures.tx(), Fixtures.CLOCK);
    private final Turn turn = turns.open("ext-1", "me", "я не ношу юбки, а теперь ношу только длинные юбки");

    @Test
    void addWritesActiveFact() {
        Fact fact = writer.apply(turn, add("skirts", "Не носит юбки", "я не ношу юбки", "stated")).orElseThrow();

        assertThat(fact.id()).isEqualTo("f-001");
        assertThat(fact.standing()).isEqualTo(Standing.STATED);
        assertThat(fact.sourceTurnId()).isEqualTo(turn.id());
        assertThat(fact.createdAt()).isEqualTo(Fixtures.NOW);
        assertThat(facts.facts).containsOnlyKeys("f-001");
    }

    @Test
    void addOnTakenScopeIsState004() {
        writer.apply(turn, add("skirts", "Не носит юбки", "я не ношу юбки", "stated"));

        assertCode(ErrorCode.STATE_004,
                () -> writer.apply(turn, add("skirts", "Носит длинные юбки", "ношу только длинные юбки", "stated")));
    }

    @Test
    void supersedeReplacesTarget() {
        Fact old = writer.apply(turn, add("skirts", "Не носит юбки", "я не ношу юбки", "stated")).orElseThrow();

        Fact next = writer.apply(turn, supersede(old.id(), "skirts", "Носит длинные юбки", "ношу только длинные юбки"))
                .orElseThrow();

        assertThat(next.supersedes()).isEqualTo(old.id());
        assertThat(next.status()).isEqualTo(FactStatus.ACTIVE);
        assertThat(facts.facts.get(old.id()).status()).isEqualTo(FactStatus.SUPERSEDED);
    }

    @Test
    void skipWritesNothing() {
        assertThat(writer.apply(turn, new ExtractorDecision("SKIP", "f-001", "", "", "", "", ""))).isEmpty();
        assertThat(facts.facts).isEmpty();
    }

    @Test
    void unknownOpIsVal012() {
        assertCode(ErrorCode.VAL_012,
                () -> writer.apply(turn, new ExtractorDecision("UPDATE", "", "constraint", "skirts", "Не носит юбки",
                        "я не ношу юбки", "stated")));
        assertCode(ErrorCode.VAL_012,
                () -> writer.apply(turn, new ExtractorDecision(null, "", "", "", "", "", "")));
    }

    @Test
    void supersedeWithoutTargetIsVal013() {
        assertCode(ErrorCode.VAL_013,
                () -> writer.apply(turn, supersede("", "skirts", "Носит длинные юбки", "ношу только длинные юбки")));
    }

    @Test
    void missingTargetIsNf001() {
        assertCode(ErrorCode.NF_001,
                () -> writer.apply(turn, supersede("f-404", "skirts", "Носит длинные юбки", "ношу только длинные юбки")));
        assertThat(facts.facts).isEmpty();
    }

    @Test
    void invalidFieldsKeepTheirCodes() {
        assertCode(ErrorCode.VAL_002, () -> writer.apply(turn,
                new ExtractorDecision("ADD", "", "habit", "skirts", "Не носит юбки", "я не ношу юбки", "stated")));
        assertCode(ErrorCode.VAL_007, () -> writer.apply(turn, add("skirts", "Не носит юбки", "я не ношу юбки", "sure")));
        assertCode(ErrorCode.VAL_009, () -> writer.apply(turn,
                supersede("skirts", "skirts", "Носит длинные юбки", "ношу только длинные юбки")));
        assertCode(ErrorCode.VAL_006, () -> writer.apply(turn, add("skirts", "Не носит юбки", "не люблю юбки", "stated")));
        assertThat(facts.facts).isEmpty();
    }
}
