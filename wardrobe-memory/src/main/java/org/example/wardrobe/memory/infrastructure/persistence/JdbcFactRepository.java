package org.example.wardrobe.memory.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactRepository;
import org.example.wardrobe.memory.domain.FactStatus;
import org.example.wardrobe.memory.domain.MemoryException;
import org.example.wardrobe.memory.infrastructure.search.RussianStems;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Факты в SQLite. {@link #insert} пишет и строку FTS — вызывать в транзакции вместе с {@link #nextId}.
 */
@Repository
public class JdbcFactRepository implements FactRepository {

    private static final String ONE_ACTIVE_VIOLATION = "fact.user_id, fact.scope";
    private static final String APPEND_ONLY_VIOLATION = ErrorCode.STATE_010.code();

    private final JdbcClient jdbc;

    public JdbcFactRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String nextId() {
        return "f-%03d".formatted(Counter.next(jdbc, "fact"));
    }

    @Override
    public Optional<Fact> findById(String id) {
        return jdbc.sql("SELECT * FROM fact WHERE id = ?").param(id).query(FactRows::toFact).optional();
    }

    @Override
    public Optional<Fact> findActive(String userId, String scope) {
        return jdbc.sql("SELECT * FROM fact WHERE user_id = ? AND scope = ? AND status = 'active'")
                .params(userId, scope).query(FactRows::toFact).optional();
    }

    @Override
    public List<Fact> findActiveByUser(String userId) {
        return jdbc.sql("SELECT * FROM fact WHERE user_id = ? AND status = 'active' ORDER BY id")
                .param(userId).query(FactRows::toFact).list();
    }

    @Override
    public List<Fact> findChain(String id) {
        List<Fact> chain = new ArrayList<>();
        Optional<Fact> current = findById(id);
        while (current.isPresent()) {
            Fact fact = current.get();
            chain.add(fact);
            current = fact.supersedes() == null ? Optional.empty() : findById(fact.supersedes());
        }
        return chain;
    }

    @Override
    public void insert(Fact fact) {
        try {
            jdbc.sql("""
                    INSERT INTO fact (id, user_id, kind, scope, fact, quote, standing, source_turn_id,
                                      source_session_id, created_at, supersedes, status, forgotten_at, forgotten_in)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(fact.id(), fact.userId(), fact.kind().value(), fact.scope(), fact.text(), fact.quote(),
                            fact.standing().value(), fact.sourceTurnId(), fact.sourceSessionId(),
                            fact.createdAt().toString(), fact.supersedes(), fact.status().value(),
                            JdbcTurnRepository.text(fact.forgottenAt()), fact.forgottenIn())
                    .update();
        } catch (DataAccessException e) {
            if (causeMessage(e).contains(ONE_ACTIVE_VIOLATION)) {
                String activeId = findActive(fact.userId(), fact.scope()).map(Fact::id).orElse("?");
                throw new MemoryException(ErrorCode.STATE_004,
                        "scope \"" + fact.scope() + "\" already has active fact " + activeId);
            }
            throw e;
        }
        if (fact.status() == FactStatus.ACTIVE) {
            jdbc.sql("INSERT INTO fact_fts (body, fact_id) VALUES (?, ?)")
                    .params(RussianStems.index(fact.text() + " " + fact.quote()), fact.id())
                    .update();
        }
    }

    @Override
    public void update(Fact fact) {
        try {
            jdbc.sql("""
                    UPDATE fact SET user_id = ?, kind = ?, scope = ?, fact = ?, quote = ?, standing = ?,
                                    source_turn_id = ?, source_session_id = ?, created_at = ?, supersedes = ?,
                                    status = ?, forgotten_at = ?, forgotten_in = ?
                    WHERE id = ?""")
                    .params(fact.userId(), fact.kind().value(), fact.scope(), fact.text(), fact.quote(),
                            fact.standing().value(), fact.sourceTurnId(), fact.sourceSessionId(),
                            fact.createdAt().toString(), fact.supersedes(), fact.status().value(),
                            JdbcTurnRepository.text(fact.forgottenAt()), fact.forgottenIn(), fact.id())
                    .update();
        } catch (DataAccessException e) {
            if (causeMessage(e).contains(APPEND_ONLY_VIOLATION)) {
                throw new MemoryException(ErrorCode.STATE_010, "fact record is append-only");
            }
            throw e;
        }
    }

    private static String causeMessage(DataAccessException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message == null ? "" : message;
    }
}
