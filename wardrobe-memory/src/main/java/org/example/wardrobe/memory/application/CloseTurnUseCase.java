package org.example.wardrobe.memory.application;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.MemoryException;
import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.domain.TurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Закрытие хода в post: ход → {@code pending}, экстракция — в очереди после коммита (AC-15). */
@Service
public class CloseTurnUseCase {

    private static final Logger log = LoggerFactory.getLogger(CloseTurnUseCase.class);

    private final TurnRepository turns;
    private final ExtractionQueue queue;
    private final TransactionTemplate tx;

    public CloseTurnUseCase(TurnRepository turns, ExtractionQueue queue, TransactionTemplate tx) {
        this.turns = turns;
        this.queue = queue;
        this.tx = tx;
    }

    public Turn close(String userId, String externalId, String assistantText) {
        Turn.checkUserId(userId);
        if (externalId == null || externalId.isBlank()) {
            throw new MemoryException(ErrorCode.VAL_011, "external_id is required");
        }
        Turn closed = tx.execute(status -> {
            Turn turn = turns.findByExternalId(externalId)
                    .orElseThrow(() -> new MemoryException(ErrorCode.NF_002, "turn " + externalId + " not found"));
            if (!turn.userId().equals(userId)) {
                throw new MemoryException(ErrorCode.STATE_002, "turn " + turn.id() + " belongs to another user");
            }
            Turn pending = turn.close(assistantText);
            turns.update(pending);
            return pending;
        });
        queue.submit(closed.id());
        log.info("close turn_id={}", closed.id());
        return closed;
    }
}
