package org.example.wardrobe.memory.application;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.MemoryException;
import org.example.wardrobe.memory.domain.Standing;
import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.domain.TurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** {@code memory_remember}: только внутри открытого хода (AC-4), цитата — из его реплики (AC-3), всегда {@code stated}. */
@Service
public class RememberUseCase {

    private static final Logger log = LoggerFactory.getLogger(RememberUseCase.class);

    private final TurnRepository turns;
    private final FactWriter writer;

    public RememberUseCase(TurnRepository turns, FactWriter writer) {
        this.turns = turns;
        this.writer = writer;
    }

    public Fact remember(String userId, String kind, String scope, String fact, String quote, String supersedes) {
        Turn.checkUserId(userId);
        Turn turn = turns.findOpen(userId).orElseThrow(() -> noOpenTurn(userId));
        boolean replaces = supersedes != null && !supersedes.isBlank();
        ExtractorDecision decision = new ExtractorDecision(replaces ? "SUPERSEDE" : "ADD", replaces ? supersedes : "",
                kind, scope, fact, quote, Standing.STATED.value());
        Fact written = writer.apply(turn, decision).orElseThrow();
        log.info("remember fact_id={} turn_id={}", written.id(), turn.id());
        return written;
    }

    static MemoryException noOpenTurn(String userId) {
        return new MemoryException(ErrorCode.STATE_006,
                "no open turn for user " + userId + ": remember/forget work only inside a turn");
    }
}
