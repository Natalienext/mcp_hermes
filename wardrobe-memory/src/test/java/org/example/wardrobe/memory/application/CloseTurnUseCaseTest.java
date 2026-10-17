package org.example.wardrobe.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.wardrobe.memory.fake.Fixtures.assertCode;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.domain.TurnStatus;
import org.example.wardrobe.memory.fake.FakeExtractionQueue;
import org.example.wardrobe.memory.fake.FakeTurnRepository;
import org.example.wardrobe.memory.fake.Fixtures;
import org.junit.jupiter.api.Test;

class CloseTurnUseCaseTest {

    private final FakeTurnRepository turns = new FakeTurnRepository();
    private final FakeExtractionQueue queue = new FakeExtractionQueue();
    private final CloseTurnUseCase useCase = new CloseTurnUseCase(turns, queue, Fixtures.tx());

    @Test
    void closesAndQueues() {
        Turn open = turns.open("ext-1", "me", "я не ношу юбки");
        queue.handler = id -> assertThat(turns.findById(id).orElseThrow().status()).isEqualTo(TurnStatus.PENDING);

        Turn closed = useCase.close("me", "ext-1", "Понял, без юбок.");

        assertThat(closed.id()).isEqualTo(open.id());
        assertThat(closed.status()).isEqualTo(TurnStatus.PENDING);
        assertThat(closed.assistantText()).isEqualTo("Понял, без юбок.");
        assertThat(queue.submitted).containsExactly(open.id());
    }

    @Test
    void unknownTurnIsNf002() {
        assertCode(ErrorCode.NF_002, () -> useCase.close("me", "ext-404", "ответ"));
        assertThat(queue.submitted).isEmpty();
    }

    @Test
    void otherUsersTurnIsState002() {
        turns.open("ext-1", "other", "чужая реплика");

        assertCode(ErrorCode.STATE_002, () -> useCase.close("me", "ext-1", "ответ"));
        assertThat(queue.submitted).isEmpty();
    }

    @Test
    void closedTwiceIsState007() {
        turns.open("ext-1", "me", "я не ношу юбки");
        useCase.close("me", "ext-1", "ответ");

        assertCode(ErrorCode.STATE_007, () -> useCase.close("me", "ext-1", "ответ"));
        assertThat(queue.submitted).hasSize(1);
    }

    @Test
    void invalidFields() {
        turns.open("ext-1", "me", "я не ношу юбки");

        assertCode(ErrorCode.VAL_001, () -> useCase.close("", "ext-1", "ответ"));
        assertCode(ErrorCode.VAL_011, () -> useCase.close("me", " ", "ответ"));
        assertCode(ErrorCode.VAL_008, () -> useCase.close("me", "ext-1", "x".repeat(20_001)));
        assertThat(queue.submitted).isEmpty();
    }
}
