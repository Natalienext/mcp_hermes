package org.example.wardrobe.memory.domain;

import java.util.Optional;

public interface TurnRepository {

    /** Следующий id {@code t-NNN}; вызывать в транзакции вставки. */
    String nextId();

    Optional<Turn> findById(String id);

    Optional<Turn> findByExternalId(String externalId);

    Optional<Turn> findOpen(String userId);

    void insert(Turn turn);

    void update(Turn turn);
}
