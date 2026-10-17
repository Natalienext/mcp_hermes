package org.example.wardrobe.memory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;

import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactKind;
import org.example.wardrobe.memory.domain.Standing;
import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.infrastructure.persistence.JdbcFactRepository;
import org.example.wardrobe.memory.infrastructure.persistence.JdbcTurnRepository;
import org.example.wardrobe.memory.infrastructure.search.FtsFactSearch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FtsFactSearchTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    @TempDir
    Path dir;

    private TestDatabase db;
    private JdbcTurnRepository turns;
    private JdbcFactRepository facts;
    private FtsFactSearch search;

    @BeforeEach
    void setUp() {
        db = new TestDatabase(dir);
        turns = new JdbcTurnRepository(db.jdbc);
        facts = new JdbcFactRepository(db.jdbc);
        search = new FtsFactSearch(db.jdbc);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private Fact add(String userId, String scope, String text, String quote, Standing standing) {
        return db.inTransaction(() -> {
            turns.findOpen(userId).ifPresent(open -> turns.update(open.abandon()));
            Turn turn = Turn.open(turns.nextId(), "ext-" + System.nanoTime(), userId, "s-1", quote, NOW);
            turns.insert(turn);
            Fact fact = Fact.create(facts.nextId(), turn, FactKind.CONSTRAINT, scope, text, quote, standing, null,
                    NOW);
            facts.insert(fact);
            return fact;
        });
    }

    @Test
    void findsOtherWordForm() {
        Fact skirts = add("me", "skirts", "Не носит юбки", "я не ношу юбки", Standing.STATED);

        assertThat(search.search("me", "Подбери мне юбку на вечер", 5)).containsExactly(skirts);
        assertThat(search.search("me", "юбкой", 5)).containsExactly(skirts);
        assertThat(search.search("me", "Подбери брюки", 5)).isEmpty();
    }

    @Test
    void onlyStatedActiveOfThisUser() {
        Fact stated = add("me", "skirts", "Не носит юбки", "я не ношу юбки", Standing.STATED);
        add("me", "skirts-mini", "Похоже, не любит мини-юбки", "мини юбки не люблю", Standing.INFERRED);
        add("someone", "skirts", "Не носит юбки", "я не ношу юбки", Standing.STATED);

        assertThat(search.search("me", "юбка", 5)).containsExactly(stated);
    }

    @Test
    void supersededIsNotFound() {
        Fact old = add("me", "skirts", "Не носит юбки", "я не ношу юбки", Standing.STATED);
        Fact next = db.inTransaction(() -> {
            Turn turn = Turn.open(turns.nextId(), "ext-next", "me", "s-2", "теперь ношу юбки", NOW);
            turns.update(turns.findOpen("me").orElseThrow().abandon());
            turns.insert(turn);
            Fact fact = Fact.create(facts.nextId(), turn, FactKind.CONSTRAINT, "skirts", "Носит юбки",
                    "теперь ношу юбки", Standing.STATED, old.id(), NOW);
            facts.update(old.supersededBy(fact));
            facts.insert(fact);
            return fact;
        });

        assertThat(search.search("me", "юбки", 5)).containsExactly(next);
    }

    @Test
    void limitGoesToSql() {
        add("me", "skirts", "Не носит юбки", "я не ношу юбки", Standing.STATED);
        add("me", "skirts-long", "Любит длинные юбки", "люблю длинные юбки", Standing.STATED);

        assertThat(search.search("me", "юбки", 5)).hasSize(2);
        assertThat(search.search("me", "юбки", 1)).hasSize(1);
    }

    @Test
    void limitZero() {
        add("me", "skirts", "Не носит юбки", "я не ношу юбки", Standing.STATED);

        assertThat(search.search("me", "юбки", 0)).isEmpty();
    }

    @Test
    void forgottenIsNotFound() {
        Fact fact = add("me", "skirts", "Не носит юбки", "я не ношу юбки", Standing.STATED);

        facts.update(fact.forget("me", "s-7", NOW));
        search.compact();

        assertThat(search.search("me", "юбки", 5)).isEmpty();
    }

    @Test
    void stopWordsOnlyQueryIsEmpty() {
        add("me", "skirts", "Не носит юбки", "я не ношу юбки", Standing.STATED);

        assertThat(search.search("me", "а я не", 5)).isEmpty();
    }
}
