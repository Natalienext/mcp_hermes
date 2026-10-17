package org.example.wardrobe.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.wardrobe.memory.fake.Fixtures.assertCode;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.TurnStatus;
import org.example.wardrobe.memory.fake.FakeTurnRepository;
import org.example.wardrobe.memory.fake.Fixtures;
import org.junit.jupiter.api.Test;

class OpenTurnUseCaseTest {

    private final FakeTurnRepository turns = new FakeTurnRepository();
    private final OpenTurnUseCase useCase = new OpenTurnUseCase(turns, Fixtures.tx(), Fixtures.CLOCK);

    @Test
    void opensTurn() {
        OpenTurnUseCase.Opened opened = useCase.open("me", "s-1", "ext-1", "я не ношу юбки");

        assertThat(opened.turn().id()).isEqualTo("t-001");
        assertThat(opened.turn().status()).isEqualTo(TurnStatus.OPEN);
        assertThat(opened.turn().createdAt()).isEqualTo(Fixtures.NOW);
        assertThat(opened.abandoned()).isEmpty();
    }

    @Test
    void previousOpenTurnIsAbandoned() {
        useCase.open("me", "s-1", "ext-1", "первая реплика");

        OpenTurnUseCase.Opened opened = useCase.open("me", "s-1", "ext-2", "вторая реплика");

        assertThat(opened.abandoned()).containsExactly("t-001");
        assertThat(turns.findById("t-001").orElseThrow().status()).isEqualTo(TurnStatus.ABANDONED);
        assertThat(turns.findOpen("me").orElseThrow().id()).isEqualTo("t-002");
    }

    @Test
    void otherUsersTurnStaysOpen() {
        useCase.open("other", "s-9", "ext-1", "чужая реплика");

        assertThat(useCase.open("me", "s-1", "ext-2", "моя реплика").abandoned()).isEmpty();
        assertThat(turns.findOpen("other")).isPresent();
    }

    @Test
    void duplicateExternalIdIsState008() {
        useCase.open("me", "s-1", "ext-1", "первая реплика");

        assertCode(ErrorCode.STATE_008, () -> useCase.open("me", "s-1", "ext-1", "повтор"));
        assertThat(turns.findOpen("me").orElseThrow().id()).isEqualTo("t-001");
    }

    @Test
    void invalidFields() {
        assertCode(ErrorCode.VAL_001, () -> useCase.open("bad user", "s-1", "ext-1", "текст"));
        assertCode(ErrorCode.VAL_011, () -> useCase.open("me", "s-1", null, "текст"));
        assertCode(ErrorCode.VAL_011, () -> useCase.open("me", "", "ext-1", "текст"));
        assertCode(ErrorCode.VAL_008, () -> useCase.open("me", "s-1", "ext-1", "x".repeat(20_001)));
    }
}
