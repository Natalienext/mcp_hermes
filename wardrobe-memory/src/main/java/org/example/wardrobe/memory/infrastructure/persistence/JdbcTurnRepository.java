package org.example.wardrobe.memory.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.domain.TurnRepository;
import org.example.wardrobe.memory.domain.TurnStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTurnRepository implements TurnRepository {

    private final JdbcClient jdbc;

    public JdbcTurnRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String nextId() {
        return "t-%03d".formatted(Counter.next(jdbc, "turn"));
    }

    @Override
    public Optional<Turn> findById(String id) {
        return jdbc.sql("SELECT * FROM turn WHERE id = ?").param(id).query(JdbcTurnRepository::toTurn).optional();
    }

    @Override
    public Optional<Turn> findByExternalId(String externalId) {
        return jdbc.sql("SELECT * FROM turn WHERE external_id = ?").param(externalId)
                .query(JdbcTurnRepository::toTurn).optional();
    }

    @Override
    public Optional<Turn> findOpen(String userId) {
        return jdbc.sql("SELECT * FROM turn WHERE user_id = ? AND status = 'open'").param(userId)
                .query(JdbcTurnRepository::toTurn).optional();
    }

    @Override
    public void insert(Turn turn) {
        jdbc.sql("""
                INSERT INTO turn (id, external_id, user_id, session_id, user_text, assistant_text, status,
                                  fail_reason, forget_requested, created_at, erased_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(turn.id(), turn.externalId(), turn.userId(), turn.sessionId(), turn.userText(),
                        turn.assistantText(), turn.status().value(), turn.failReason(), turn.forgetRequested() ? 1 : 0,
                        turn.createdAt().toString(), text(turn.erasedAt()))
                .update();
    }

    @Override
    public void update(Turn turn) {
        jdbc.sql("""
                UPDATE turn SET user_text = ?, assistant_text = ?, status = ?, fail_reason = ?, forget_requested = ?,
                                erased_at = ?
                WHERE id = ?""")
                .params(turn.userText(), turn.assistantText(), turn.status().value(), turn.failReason(),
                        turn.forgetRequested() ? 1 : 0, text(turn.erasedAt()), turn.id())
                .update();
    }

    private static Turn toTurn(ResultSet rs, int row) throws SQLException {
        return new Turn(rs.getString("id"), rs.getString("external_id"), rs.getString("user_id"),
                rs.getString("session_id"), rs.getString("user_text"), rs.getString("assistant_text"),
                TurnStatus.fromValue(rs.getString("status")), rs.getString("fail_reason"),
                rs.getInt("forget_requested") == 1, Instant.parse(rs.getString("created_at")),
                instant(rs.getString("erased_at")));
    }

    static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    static Instant instant(String text) {
        return text == null ? null : Instant.parse(text);
    }
}
