package org.example.wardrobe.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.wardrobe.memory.fake.Fixtures.assertCode;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactKind;
import org.example.wardrobe.memory.domain.RecallBlock;
import org.example.wardrobe.memory.domain.Standing;
import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.fake.FakeFactSearch;
import org.example.wardrobe.memory.fake.Fixtures;
import org.junit.jupiter.api.Test;

class RecallUseCaseTest {

    private final FakeFactSearch search = new FakeFactSearch();
    private final RecallUseCase useCase = new RecallUseCase(search, Fixtures.CLOCK, 5);
    private final Turn turn = Turn.open("t-001", "ext-1", "me", "s-1", "я не ношу юбки и люблю синий", Fixtures.NOW);

    private Fact fact(String id, String scope, String quote, Standing standing) {
        return Fact.create(id, turn, FactKind.CONSTRAINT, scope, "Факт " + scope, quote, standing, null, Fixtures.NOW);
    }

    @Test
    void rendersRecallableFacts() {
        search.results.add(fact("f-001", "skirts", "я не ношу юбки", Standing.STATED));

        RecallBlock block = useCase.recall("me", "что надеть вместо юбки", null);

        assertThat(block.ids()).containsExactly("f-001");
        assertThat(block.context()).startsWith(RecallBlock.HEADER)
                .endsWith("f-001 | constraint | сегодня | Факт skirts | «я не ношу юбки»");
        assertThat(search.lastLimit).isEqualTo(5);
    }

    @Test
    void inferredIsNotRecalled() {
        search.results.add(fact("f-001", "skirts", "я не ношу юбки", Standing.INFERRED));
        search.results.add(fact("f-002", "color-blue", "люблю синий", Standing.STATED));

        assertThat(useCase.recall("me", "юбки", null).ids()).containsExactly("f-002");
    }

    @Test
    void limitIsMinOfRequestedAndConfigured() {
        useCase.recall("me", "юбки", 3);
        assertThat(search.lastLimit).isEqualTo(3);

        useCase.recall("me", "юбки", 50);
        assertThat(search.lastLimit).isEqualTo(5);
    }

    @Test
    void limitZeroGivesEmptyContext() {
        search.results.add(fact("f-001", "skirts", "я не ношу юбки", Standing.STATED));

        RecallBlock block = useCase.recall("me", "юбки", 0);

        assertThat(search.lastLimit).isZero();
        assertThat(block.context()).isEmpty();
        assertThat(block.ids()).isEmpty();
    }

    @Test
    void configuredZeroGivesEmptyContext() {
        search.results.add(fact("f-001", "skirts", "я не ношу юбки", Standing.STATED));

        assertThat(new RecallUseCase(search, Fixtures.CLOCK, 0).recall("me", "юбки", null).context()).isEmpty();
        assertThat(search.lastLimit).isZero();
    }

    @Test
    void errors() {
        assertCode(ErrorCode.VAL_010, () -> useCase.recall("me", "юбки", -1));
        assertCode(ErrorCode.VAL_001, () -> useCase.recall(null, "юбки", null));
    }
}
