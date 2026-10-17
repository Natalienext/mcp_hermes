package org.example.wardrobe.memory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactRepository;
import org.example.wardrobe.memory.domain.FactSearch;
import org.example.wardrobe.memory.domain.MemoryException;
import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.domain.TurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code memory_forget} (I7, AC-10, AC-11): факт забыт, прежние версии и реплики ходов-источников стёрты, текущий ход
 * помечен (D6) — одной транзакцией; после коммита FTS сжимается (I9).
 */
@Service
public class ForgetUseCase {

    private static final Logger log = LoggerFactory.getLogger(ForgetUseCase.class);

    private final TurnRepository turns;
    private final FactRepository facts;
    private final FactSearch search;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ForgetUseCase(TurnRepository turns, FactRepository facts, FactSearch search, TransactionTemplate tx,
            Clock clock) {
        this.turns = turns;
        this.facts = facts;
        this.search = search;
        this.tx = tx;
        this.clock = clock;
    }

    public Fact forget(String userId, String factId) {
        Turn.checkUserId(userId);
        Fact.checkId(factId);
        Fact forgotten = tx.execute(status -> {
            Turn current = turns.findOpen(userId).orElseThrow(() -> RememberUseCase.noOpenTurn(userId));
            List<Fact> chain = facts.findChain(factId);
            if (chain.isEmpty()) {
                throw new MemoryException(ErrorCode.NF_001, "fact " + factId + " not found");
            }
            Instant now = clock.instant();
            Fact head = chain.get(0).forget(userId, current.sessionId(), now);
            facts.update(head);
            for (Fact older : chain.subList(1, chain.size())) {
                facts.update(older.erase(now));
            }
            Map<String, Turn> touched = new LinkedHashMap<>();
            for (Fact fact : chain) {
                touched.computeIfAbsent(fact.sourceTurnId(),
                        id -> turns.findById(id).map(turn -> turn.eraseUserText(now)).orElse(null));
            }
            Turn source = touched.get(current.id());
            touched.put(current.id(), (source == null ? current : source).requestForget());
            touched.values().stream().filter(turn -> turn != null).forEach(turns::update);
            return head;
        });
        search.compact();
        log.info("forget fact_id={} session_id={}", forgotten.id(), forgotten.forgottenIn());
        return forgotten;
    }
}
