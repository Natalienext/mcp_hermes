package org.example.wardrobe.memory.infrastructure;

import java.nio.file.Path;
import java.util.function.Supplier;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** База на файле с настройками как в {@code application.yml}: пул из одного соединения, {@code secure_delete}. */
final class TestDatabase implements AutoCloseable {

    final Path file;
    final HikariDataSource dataSource;
    final JdbcClient jdbc;
    final TransactionTemplate tx;

    TestDatabase(Path dir) {
        file = dir.resolve("memory.db");
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:" + file + "?foreign_keys=on");
        dataSource.setMaximumPoolSize(1);
        dataSource.setConnectionInitSql("PRAGMA secure_delete=ON");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        populator.setSeparator("@@");
        populator.execute(dataSource);
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    <T> T inTransaction(Supplier<T> action) {
        return tx.execute(status -> action.get());
    }

    void inTransaction(Runnable action) {
        tx.executeWithoutResult(status -> action.run());
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
