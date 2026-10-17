package org.example.wardrobe.memory.fake;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.domain.TurnRepository;
import org.example.wardrobe.memory.domain.TurnStatus;

public class FakeTurnRepository implements TurnRepository {

    public final Map<String, Turn> turns = new LinkedHashMap<>();
    private int counter;

    @Override
    public String nextId() {
        return "t-%03d".formatted(++counter);
    }

    @Override
    public Optional<Turn> findById(String id) {
        return Optional.ofNullable(turns.get(id));
    }

    @Override
    public Optional<Turn> findByExternalId(String externalId) {
        return turns.values().stream().filter(turn -> turn.externalId().equals(externalId)).findFirst();
    }

    @Override
    public Optional<Turn> findOpen(String userId) {
        return turns.values().stream()
                .filter(turn -> turn.userId().equals(userId) && turn.status() == TurnStatus.OPEN)
                .findFirst();
    }

    @Override
    public void insert(Turn turn) {
        turns.put(turn.id(), turn);
    }

    @Override
    public void update(Turn turn) {
        turns.put(turn.id(), turn);
    }

    /** Ход в базе: открыт с репликой {@code userText}. */
    public Turn open(String externalId, String userId, String userText) {
        Turn turn = Turn.open(nextId(), externalId, userId, "s-" + externalId, userText, Fixtures.NOW);
        insert(turn);
        return turn;
    }
}
