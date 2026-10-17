package org.example.wardrobe.memory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.example.wardrobe.memory.application.ExtractorDecision;
import org.example.wardrobe.memory.application.FactWriter;
import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactKind;
import org.example.wardrobe.memory.domain.FactStatus;
import org.example.wardrobe.memory.domain.MemoryException;
import org.example.wardrobe.memory.domain.Standing;
import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.infrastructure.persistence.JdbcFactRepository;
import org.example.wardrobe.memory.infrastructure.persistence.JdbcTurnRepository;
import org.example.wardrobe.memory.infrastructure.search.FtsFactSearch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;

class JdbcFactRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    @TempDir
    Path dir;

    private TestDatabase db;
    private JdbcTurnRepository turns;
    private JdbcFactRepository facts;

    @BeforeEach
    void setUp() {
        db = new TestDatabase(dir);
        turns = new JdbcTurnRepository(db.jdbc);
        facts = new JdbcFactRepository(db.jdbc);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private Turn turn(String userText) {
        return db.inTransaction(() -> {
            turns.findOpen("me").ifPresent(open -> turns.update(open.abandon()));
            Turn turn = Turn.open(turns.nextId(), "ext-" + System.nanoTime(), "me", "s-1", userText, NOW);
            turns.insert(turn);
            return turn;
        });
    }

    private Fact add(Turn turn, String scope, String text, String quote, Standing standing, String supersedes) {
        return db.inTransaction(() -> {
            Fact fact = Fact.create(facts.nextId(), turn, FactKind.CONSTRAINT, scope, text, quote, standing,
                    supersedes, NOW);
            facts.insert(fact);
            return fact;
        });
    }

    private Fact skirts() {
        return add(turn("я не ношу юбки"), "skirts", "Не носит юбки", "я не ношу юбки", Standing.STATED, null);
    }

    private Fact supersede(Fact old, String text, String quote) {
        return db.inTransaction(() -> {
            Fact next = Fact.create(facts.nextId(), turn(quote), FactKind.CONSTRAINT, old.scope(), text, quote,
                    Standing.STATED, old.id(), NOW);
            facts.update(old.supersededBy(next));
            facts.insert(next);
            return next;
        });
    }

    private List<String> ftsIds() {
        return db.jdbc.sql("SELECT fact_id FROM fact_fts ORDER BY fact_id").query(String.class).list();
    }

    @Test
    void roundTripAndFinders() {
        Fact fact = skirts();
        Fact blue = add(turn("люблю синий"), "color-blue", "Любит синий", "люблю синий", Standing.INFERRED, null);

        assertThat(fact.id()).isEqualTo("f-001");
        assertThat(facts.findById("f-001")).contains(fact);
        assertThat(facts.findById("f-999")).isEmpty();
        assertThat(facts.findActive("me", "skirts")).contains(fact);
        assertThat(facts.findActive("me", "shorts")).isEmpty();
        assertThat(facts.findActiveByUser("me")).containsExactly(fact, blue);
        assertThat(facts.findActiveByUser("someone")).isEmpty();
    }

    @Test
    void factOneActive() {
        Fact first = skirts();

        assertThatThrownBy(() -> add(turn("не ношу юбки вообще"), "skirts", "Не носит юбки вообще",
                "не ношу юбки вообще", Standing.INFERRED, null))
                .isInstanceOfSatisfying(MemoryException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.STATE_004))
                .hasMessage("scope \"skirts\" already has active fact " + first.id());
        assertThat(facts.findActiveByUser("me")).containsExactly(first);
        assertThat(facts.nextId()).as("id отказанной вставки откатился вместе с ней").isEqualTo("f-002");
    }

    @Test
    void supersedeAndChain() {
        Fact first = skirts();
        Fact second = supersede(first, "Иногда носит юбки", "иногда ношу юбки");
        Fact third = supersede(second, "Носит юбки", "ношу юбки");

        assertThat(facts.findById(first.id())).map(Fact::status).contains(FactStatus.SUPERSEDED);
        assertThat(facts.findActive("me", "skirts")).contains(third);
        assertThat(facts.findChain(third.id())).extracting(Fact::id)
                .containsExactly(third.id(), second.id(), first.id());
        assertThat(facts.findChain("f-999")).isEmpty();
        assertThat(ftsIds()).containsExactly(third.id());
    }

    @Test
    void supersedeInOneTransaction() {
        FactWriter writer = new FactWriter(facts, db.tx, Clock.fixed(NOW, ZoneOffset.UTC));
        Fact first = skirts();

        Fact next = writer.apply(turn("теперь ношу длинные юбки"),
                new ExtractorDecision("SUPERSEDE", first.id(), "constraint", "skirts", "Носит длинные юбки",
                        "ношу длинные юбки", "stated")).orElseThrow();

        assertThat(facts.findById(first.id())).map(Fact::status).contains(FactStatus.SUPERSEDED);
        assertThat(facts.findActive("me", "skirts")).contains(next);
        assertThat(ftsIds()).containsExactly(next.id());

        Turn unsaved = Turn.open("t-099", "ext-unsaved", "me", "s-1", "снова не ношу юбки", NOW);
        assertThatThrownBy(() -> writer.apply(unsaved, new ExtractorDecision("SUPERSEDE", next.id(), "constraint",
                "skirts", "Снова не носит юбки", "снова не ношу юбки", "stated")))
                .as("вставка новой версии падает на внешнем ключе после update старой")
                .isInstanceOf(DataAccessException.class);
        assertThat(facts.findById(next.id())).map(Fact::status).as("update старой откатился").contains(FactStatus.ACTIVE);
        assertThat(facts.findActiveByUser("me")).containsExactly(next);
        assertThat(ftsIds()).containsExactly(next.id());
    }

    @Test
    void appendOnly() {
        Fact fact = skirts();

        assertState010(() -> facts.update(new Fact(fact.id(), fact.userId(), fact.kind(), "shorts", fact.text(),
                fact.quote(), fact.standing(), fact.sourceTurnId(), fact.sourceSessionId(), fact.createdAt(),
                fact.supersedes(), fact.status(), null, null)));
        assertState010(() -> facts.update(new Fact(fact.id(), fact.userId(), fact.kind(), fact.scope(),
                "Носит юбки", fact.quote(), fact.standing(), fact.sourceTurnId(), fact.sourceSessionId(),
                fact.createdAt(), fact.supersedes(), fact.status(), null, null)));
        assertState010(() -> facts.update(new Fact(fact.id(), fact.userId(), fact.kind(), fact.scope(), fact.text(),
                fact.quote(), Standing.INFERRED, fact.sourceTurnId(), fact.sourceSessionId(), fact.createdAt(),
                fact.supersedes(), fact.status(), null, null)));

        Fact next = supersede(fact, "Иногда носит юбки", "иногда ношу юбки");
        Fact superseded = facts.findById(fact.id()).orElseThrow();
        assertState010(() -> facts.update(new Fact(superseded.id(), superseded.userId(), superseded.kind(),
                superseded.scope(), superseded.text(), superseded.quote(), superseded.standing(),
                superseded.sourceTurnId(), superseded.sourceSessionId(), superseded.createdAt(),
                superseded.supersedes(), FactStatus.ACTIVE, null, null)));
        assertThat(facts.findById(fact.id())).contains(superseded);
        assertThat(facts.findActive("me", "skirts")).contains(next);
    }

    @Test
    void eraseSuperseded() {
        Fact first = skirts();
        supersede(first, "Иногда носит юбки", "иногда ношу юбки");
        Fact superseded = facts.findById(first.id()).orElseThrow();

        Fact erased = superseded.erase(NOW.plusSeconds(60));
        facts.update(erased);

        Fact stored = facts.findById(first.id()).orElseThrow();
        assertThat(stored).isEqualTo(erased);
        assertThat(stored.status()).isEqualTo(FactStatus.SUPERSEDED);
        assertThat(stored.text()).isEmpty();
        assertThat(stored.quote()).isEmpty();
    }

    @Test
    void forgottenChecks() {
        Fact fact = skirts();

        assertThatThrownBy(() -> db.jdbc.sql("UPDATE fact SET status = 'forgotten' WHERE id = ?")
                .param(fact.id()).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.jdbc.sql("UPDATE fact SET forgotten_at = ? WHERE id = ?")
                .params(NOW.toString(), fact.id()).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.jdbc.sql("UPDATE fact SET fact = '', quote = '' WHERE id = ?")
                .param(fact.id()).update()).isInstanceOf(DataAccessException.class);

        Fact forgotten = fact.forget("me", "s-7", NOW.plusSeconds(60));
        facts.update(forgotten);
        assertThat(facts.findById(fact.id())).contains(forgotten);
        assertThat(facts.findActive("me", "skirts")).isEmpty();
    }

    @Test
    void ftsRowOnInsertAndRemovedOnStatusChange() {
        Fact fact = skirts();
        Fact inferred = add(turn("люблю синий"), "color-blue", "Любит синий", "люблю синий", Standing.INFERRED, null);

        assertThat(ftsIds()).containsExactly(fact.id(), inferred.id());
        assertThat(db.jdbc.sql("SELECT body FROM fact_fts WHERE fact_id = ?").param(fact.id())
                .query(String.class).single()).isEqualTo("нос юбк нош юбк");

        facts.update(fact.forget("me", "s-7", NOW));
        assertThat(ftsIds()).containsExactly(inferred.id());
    }

    @Test
    void forgottenTextNotInFile() throws IOException {
        String secret = "мой размер сорок шестой лимонный";
        Turn turn = turn("Запомни: " + secret);
        Fact fact = add(turn, "size", "Носит сорок шестой размер", secret, Standing.STATED, null);
        assertThat(contains(db.file, secret)).isTrue();

        db.inTransaction(() -> {
            facts.update(fact.forget("me", "s-7", NOW));
            turns.update(turns.findById(turn.id()).orElseThrow().eraseUserText(NOW));
        });
        new FtsFactSearch(db.jdbc).compact();

        assertThat(contains(db.file, secret)).isFalse();
        assertThat(contains(db.file, "Носит сорок шестой размер")).isFalse();
    }

    private static boolean contains(Path file, String text) throws IOException {
        byte[] haystack = Files.readAllBytes(file);
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void assertState010(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(MemoryException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.STATE_010))
                .hasMessage("fact record is append-only");
    }
}
