package org.example.wardrobe.memory.application;

import java.time.Clock;
import java.util.Optional;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactKind;
import org.example.wardrobe.memory.domain.FactRepository;
import org.example.wardrobe.memory.domain.MemoryException;
import org.example.wardrobe.memory.domain.Standing;
import org.example.wardrobe.memory.domain.Turn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Запись одного решения (экстрактор или remember): {@code ADD}, {@code SUPERSEDE}, {@code SKIP}. Каждое решение —
 * своя транзакция; правила — в {@link Fact}, здесь только координация.
 */
@Service
public class FactWriter {

    private final FactRepository facts;
    private final TransactionTemplate tx;
    private final Clock clock;

    public FactWriter(FactRepository facts, TransactionTemplate tx, Clock clock) {
        this.facts = facts;
        this.tx = tx;
        this.clock = clock;
    }

    /** Записанный факт; для {@code SKIP} — пусто. */
    public Optional<Fact> apply(Turn source, ExtractorDecision decision) {
        return switch (decision.op() == null ? "" : decision.op()) {
            case "SKIP" -> Optional.empty();
            case "ADD" -> Optional.of(tx.execute(status -> add(source, decision)));
            case "SUPERSEDE" -> {
                if (decision.targetId() == null || decision.targetId().isBlank()) {
                    throw new MemoryException(ErrorCode.VAL_013, "target_id is required for SUPERSEDE");
                }
                Fact.checkId(decision.targetId());
                yield Optional.of(tx.execute(status -> supersede(source, decision)));
            }
            default -> throw new MemoryException(ErrorCode.VAL_012, "unknown op \"" + decision.op() + "\"");
        };
    }

    private Fact add(Turn source, ExtractorDecision decision) {
        Fact fact = create(source, decision, null);
        fact.checkScopeFree(facts.findActive(fact.userId(), fact.scope()));
        facts.insert(fact);
        return fact;
    }

    private Fact supersede(Turn source, ExtractorDecision decision) {
        Fact target = facts.findById(decision.targetId())
                .orElseThrow(() -> new MemoryException(ErrorCode.NF_001, "fact " + decision.targetId() + " not found"));
        Fact next = create(source, decision, target.id());
        facts.update(target.supersededBy(next));
        facts.insert(next);
        return next;
    }

    private Fact create(Turn source, ExtractorDecision decision, String supersedes) {
        FactKind kind = FactKind.parse(decision.kind());
        Standing standing = Standing.parse(decision.standing());
        return Fact.create(facts.nextId(), source, kind, decision.scope(), decision.fact(), decision.quote(),
                standing, supersedes, clock.instant());
    }
}
