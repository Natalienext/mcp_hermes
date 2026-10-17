package org.example.wardrobe.memory.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.example.wardrobe.memory.application.ExtractorDecision;
import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.MemoryException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Общее для unit-тестов use case: время, транзакции без базы, решения экстрактора. */
public final class Fixtures {

    public static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private Fixtures() {
    }

    /** {@link TransactionTemplate} для фейков: границы есть, отката нет. */
    public static TransactionTemplate tx() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        });
    }

    public static ExtractorDecision add(String scope, String fact, String quote, String standing) {
        return new ExtractorDecision("ADD", "", "constraint", scope, fact, quote, standing);
    }

    public static ExtractorDecision supersede(String targetId, String scope, String fact, String quote) {
        return new ExtractorDecision("SUPERSEDE", targetId, "constraint", scope, fact, quote, "stated");
    }

    public static void assertCode(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(MemoryException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
