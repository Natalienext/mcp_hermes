package org.example.wardrobe.memory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class SchemaTest {

    @TempDir
    Path dir;

    @Test
    void schemaCreatesTablesWithFts5AndIsRepeatable() {
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("memory.db"), true);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        populator.setSeparator("@@");

        populator.execute(dataSource);
        populator.execute(dataSource);

        JdbcClient jdbc = JdbcClient.create(dataSource);
        List<String> objects = jdbc.sql("SELECT name FROM sqlite_master ORDER BY name").query(String.class).list();
        assertThat(objects).contains("counter", "turn", "fact", "fact_fts", "turn_one_open", "fact_one_active",
                "fact_source", "fact_fts_close", "fact_append_only");
        assertThat(jdbc.sql("SELECT name || '=' || n FROM counter ORDER BY name").query(String.class).list())
                .containsExactly("fact=0", "turn=0");

        jdbc.sql("INSERT INTO fact_fts (body, fact_id) VALUES ('нош юбк', 'f-001')").update();
        assertThat(jdbc.sql("SELECT fact_id FROM fact_fts WHERE fact_fts MATCH '\"юбк\"'").query(String.class).list())
                .containsExactly("f-001");
        dataSource.destroy();
    }
}
