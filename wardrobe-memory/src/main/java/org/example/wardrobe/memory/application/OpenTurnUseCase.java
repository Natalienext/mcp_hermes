package org.example.wardrobe.memory.application;

import java.time.Clock;
import java.util.List;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.MemoryException;
import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.domain.TurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Открытие хода в pre: прежний открытый ход пользователя прерывается (I8, AC-17) в той же транзакции. */
@Service
public class OpenTurnUseCase {

    private static final Logger log = LoggerFactory.getLogger(OpenTurnUseCase.class);

    public record Opened(Turn turn, List<String> abandoned) {
    }

    private final TurnRepository turns;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OpenTurnUseCase(TurnRepository turns, TransactionTemplate tx, Clock clock) {
        this.turns = turns;
        this.tx = tx;
        this.clock = clock;
    }

    public Opened open(String userId, String sessionId, String externalId, String userText) {
        Turn.checkUserId(userId);
        Opened opened = tx.execute(status -> {
            if (externalId != null && turns.findByExternalId(externalId).isPresent()) {
                throw new MemoryException(ErrorCode.STATE_008, "turn " + externalId + " already exists");
            }
            List<String> abandoned = turns.findOpen(userId)
                    .map(open -> {
                        turns.update(open.abandon());
                        return List.of(open.id());
                    })
                    .orElse(List.of());
            Turn turn = Turn.open(turns.nextId(), externalId, userId, sessionId, userText, clock.instant());
            turns.insert(turn);
            return new Opened(turn, abandoned);
        });
        log.info("open turn_id={} abandoned={}", opened.turn().id(), opened.abandoned());
        return opened;
    }
}
