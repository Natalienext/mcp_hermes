package org.example.wardrobe.memory.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactKind;
import org.example.wardrobe.memory.domain.FactStatus;
import org.example.wardrobe.memory.domain.Standing;

/** Строка таблицы {@code fact} → {@link Fact}; общая для репозитория и поиска. */
public final class FactRows {

    private FactRows() {
    }

    public static Fact toFact(ResultSet rs, int row) throws SQLException {
        return new Fact(rs.getString("id"), rs.getString("user_id"), FactKind.parse(rs.getString("kind")),
                rs.getString("scope"), rs.getString("fact"), rs.getString("quote"),
                Standing.parse(rs.getString("standing")), rs.getString("source_turn_id"),
                rs.getString("source_session_id"), Instant.parse(rs.getString("created_at")),
                rs.getString("supersedes"), FactStatus.fromValue(rs.getString("status")),
                JdbcTurnRepository.instant(rs.getString("forgotten_at")), rs.getString("forgotten_in"));
    }
}
