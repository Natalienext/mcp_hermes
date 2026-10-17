package org.example.wardrobe.memory.application;

import java.util.List;
import java.util.Optional;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactRepository;
import org.example.wardrobe.memory.domain.MemoryException;
import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.domain.TurnRepository;
import org.example.wardrobe.memory.domain.TurnStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Разбор закрытого хода (D7, D11): модель вызывается без транзакции, каждое решение пишется отдельно, отказ одного
 * не отменяет другие (AC-1). Любая необработанная ошибка — ход {@code failed} с {@code MEM-INT-001}.
 */
@Service
public class ExtractTurnUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExtractTurnUseCase.class);

    private final TurnRepository turns;
    private final FactRepository facts;
    private final Extractor extractor;
    private final FactWriter writer;

    public ExtractTurnUseCase(TurnRepository turns, FactRepository facts, Extractor extractor, FactWriter writer) {
        this.turns = turns;
        this.facts = facts;
        this.extractor = extractor;
        this.writer = writer;
    }

    public void process(String turnId) {
        try {
            extract(turnId);
        } catch (Throwable e) {
            log.error("extract failed turn_id={} code={}", turnId, ErrorCode.INT_001.code(), e);
            try {
                turns.findById(turnId)
                        .filter(turn -> turn.status() == TurnStatus.PENDING)
                        .ifPresent(turn -> turns.update(
                                turn.markFailed(ErrorCode.INT_001.code() + ": " + e.getClass().getSimpleName())));
            } catch (Throwable again) {
                log.error("extract failed turn_id={} stays pending", turnId, again);
            }
        }
    }

    private void extract(String turnId) {
        Optional<Turn> loaded = turns.findById(turnId);
        if (loaded.isEmpty() || loaded.get().status() != TurnStatus.PENDING) {
            return;
        }
        Turn turn = loaded.get();
        if (!turn.needsExtraction()) {
            turns.update(turn.markDone());
            log.info("extract skip turn_id={} reason=forget requested", turnId);
            return;
        }
        List<Fact> candidates = facts.findActiveByUser(turn.userId());
        List<ExtractorDecision> decisions;
        try {
            decisions = extractor.extract(ExtractionInput.of(turn, candidates));
        } catch (ExtractionFailedException e) {
            turns.update(turn.markFailed(e.code().code() + ": " + e.getMessage()));
            log.warn("extract failed turn_id={} code={}", turnId, e.code().code());
            return;
        }
        for (ExtractorDecision decision : decisions) {
            try {
                writer.apply(turn, decision).ifPresentOrElse(
                        fact -> log.info("accept op={} fact_id={} scope={}", decision.op(), fact.id(), fact.scope()),
                        () -> log.info("accept op={} target_id={}", decision.op(), decision.targetId()));
            } catch (MemoryException e) {
                log.info("reject op={} scope={} code={}", decision.op(), decision.scope(), e.code().code());
            }
        }
        turns.update(turn.markDone());
        log.info("extract done turn_id={} decisions={}", turnId, decisions.size());
    }
}
