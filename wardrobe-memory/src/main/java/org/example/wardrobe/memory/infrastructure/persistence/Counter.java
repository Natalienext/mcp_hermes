package org.example.wardrobe.memory.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

final class Counter {

    private Counter() {
    }

    static long next(JdbcClient jdbc, String name) {
        return jdbc.sql("UPDATE counter SET n = n + 1 WHERE name = ? RETURNING n").param(name)
                .query(Long.class).single();
    }
}
