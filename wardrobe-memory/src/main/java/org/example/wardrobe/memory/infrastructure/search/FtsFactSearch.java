package org.example.wardrobe.memory.infrastructure.search;

import java.util.List;

import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactSearch;
import org.example.wardrobe.memory.infrastructure.persistence.FactRows;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Recall через FTS5 по основам слов (D1); лимит уходит в {@code LIMIT ?} (I10). */
@Component
public class FtsFactSearch implements FactSearch {

    private final JdbcClient jdbc;

    public FtsFactSearch(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Fact> search(String userId, String query, int limit) {
        String match = RussianStems.query(query);
        if (match.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT f.* FROM fact_fts JOIN fact f ON f.id = fact_fts.fact_id
                WHERE fact_fts MATCH ? AND f.user_id = ? AND f.status = 'active' AND f.standing = 'stated'
                ORDER BY fact_fts.rank LIMIT ?""")
                .params(match, userId, limit)
                .query(FactRows::toFact)
                .list();
    }

    @Override
    public void compact() {
        jdbc.sql("INSERT INTO fact_fts (fact_fts) VALUES ('optimize')").update();
    }
}
