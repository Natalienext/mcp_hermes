package org.example.wardrobe.memory.fake;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactRepository;
import org.example.wardrobe.memory.domain.FactStatus;
import org.example.wardrobe.memory.domain.MemoryException;

public class FakeFactRepository implements FactRepository {

    public final Map<String, Fact> facts = new LinkedHashMap<>();
    private int counter;

    @Override
    public String nextId() {
        return "f-%03d".formatted(++counter);
    }

    @Override
    public Optional<Fact> findById(String id) {
        return Optional.ofNullable(facts.get(id));
    }

    @Override
    public Optional<Fact> findActive(String userId, String scope) {
        return facts.values().stream()
                .filter(fact -> fact.userId().equals(userId) && fact.scope().equals(scope)
                        && fact.status() == FactStatus.ACTIVE)
                .findFirst();
    }

    @Override
    public List<Fact> findActiveByUser(String userId) {
        return facts.values().stream()
                .filter(fact -> fact.userId().equals(userId) && fact.status() == FactStatus.ACTIVE)
                .toList();
    }

    @Override
    public List<Fact> findChain(String id) {
        List<Fact> chain = new ArrayList<>();
        Fact current = facts.get(id);
        while (current != null) {
            chain.add(current);
            current = current.supersedes() == null ? null : facts.get(current.supersedes());
        }
        return chain;
    }

    /** Как индекс {@code fact_one_active}: вторая действующая запись на теме — {@code MEM-STATE-004}. */
    @Override
    public void insert(Fact fact) {
        if (fact.status() == FactStatus.ACTIVE) {
            findActive(fact.userId(), fact.scope()).ifPresent(active -> {
                throw new MemoryException(ErrorCode.STATE_004,
                        "scope \"" + fact.scope() + "\" already has active fact " + active.id());
            });
        }
        facts.put(fact.id(), fact);
    }

    @Override
    public void update(Fact fact) {
        facts.put(fact.id(), fact);
    }
}
