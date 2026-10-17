package org.example.wardrobe.memory.domain;

import java.util.List;
import java.util.Optional;

public interface FactRepository {

    /** Следующий id {@code f-NNN}; вызывать в транзакции вставки. */
    String nextId();

    Optional<Fact> findById(String id);

    Optional<Fact> findActive(String userId, String scope);

    /** Все действующие факты пользователя, {@code stated} и {@code inferred} (D7). */
    List<Fact> findActiveByUser(String userId);

    /** Факт и все его прежние версии по {@code supersedes}, начиная с него; нет факта — пусто. */
    List<Fact> findChain(String id);

    /** Новая запись; на теме уже есть действующая — {@code MEM-STATE-004}. */
    void insert(Fact fact);

    /** Смена статуса или стирание текста; запрещённое изменение — {@code MEM-STATE-010}. */
    void update(Fact fact);
}
