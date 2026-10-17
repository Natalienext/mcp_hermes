package org.example.wardrobe.memory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;

import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.domain.TurnStatus;
import org.example.wardrobe.memory.infrastructure.persistence.JdbcTurnRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;

class JdbcTurnRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    @TempDir
    Path dir;

    private TestDatabase db;
    private JdbcTurnRepository turns;

    @BeforeEach
    void setUp() {
        db = new TestDatabase(dir);
        turns = new JdbcTurnRepository(db.jdbc);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private Turn openTurn(String externalId, String userId) {
        return db.inTransaction(() -> {
            Turn turn = Turn.open(turns.nextId(), externalId, userId, "s-1", "я не ношу юбки", NOW);
            turns.insert(turn);
            return turn;
        });
    }

    @Test
    void nextIdIsSequential() {
        assertThat(turns.nextId()).isEqualTo("t-001");
        assertThat(turns.nextId()).isEqualTo("t-002");
    }

    @Test
    void nextIdRollsBackWithInsert() {
        assertThatThrownBy(() -> db.inTransaction(() -> {
            turns.nextId();
            throw new IllegalStateException("insert failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(turns.nextId()).isEqualTo("t-001");
    }

    @Test
    void roundTripAndFinders() {
        Turn turn = openTurn("ext-1", "me");

        assertThat(turns.findById(turn.id())).contains(turn);
        assertThat(turns.findByExternalId("ext-1")).contains(turn);
        assertThat(turns.findOpen("me")).contains(turn);
        assertThat(turns.findOpen("someone")).isEmpty();
        assertThat(turns.findByExternalId("ext-2")).isEmpty();
    }

    @Test
    void updateKeepsTransitions() {
        Turn turn = openTurn("ext-1", "me");
        Turn failed = turn.requestForget().close("ответ").markFailed("MEM-EXT-001: extractor unavailable: x")
                .eraseUserText(NOW.plusSeconds(5));

        turns.update(failed);

        assertThat(turns.findById(turn.id())).contains(failed);
        assertThat(turns.findOpen("me")).isEmpty();
    }

    @Test
    void turnOneOpen() {
        Turn first = openTurn("ext-1", "me");

        assertThatThrownBy(() -> openTurn("ext-2", "me")).isInstanceOf(DataAccessException.class);
        openTurn("ext-3", "someone");

        db.inTransaction(() -> {
            turns.update(first.abandon());
            turns.insert(Turn.open(turns.nextId(), "ext-4", "me", "s-1", "новая реплика", NOW));
        });
        assertThat(turns.findOpen("me")).map(Turn::externalId).contains("ext-4");
        assertThat(turns.findById(first.id())).map(Turn::status).contains(TurnStatus.ABANDONED);
    }

    @Test
    void externalIdIsUnique() {
        openTurn("ext-1", "me");
        Turn abandoned = turns.findOpen("me").orElseThrow().abandon();
        turns.update(abandoned);

        assertThatThrownBy(() -> openTurn("ext-1", "me")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void erasedTextCheck() {
        Turn turn = openTurn("ext-1", "me");

        assertThatThrownBy(() -> db.jdbc.sql("UPDATE turn SET user_text = '' WHERE id = ?").param(turn.id()).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.jdbc.sql("UPDATE turn SET erased_at = ? WHERE id = ?")
                .params(NOW.toString(), turn.id()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
