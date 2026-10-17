package org.example.wardrobe.memory.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Факт о пользователе. Формат полей — в compact constructor (I2), переходы — методами (I3, I4, I7).
 */
public record Fact(
        String id,
        String userId,
        FactKind kind,
        String scope,
        String text,
        String quote,
        Standing standing,
        String sourceTurnId,
        String sourceSessionId,
        Instant createdAt,
        String supersedes,
        FactStatus status,
        Instant forgottenAt,
        String forgottenIn) {

    public static final int MAX_TEXT = 200;
    static final int MAX_SCOPE = 48;
    static final int MAX_RECALL_QUOTE = 40;
    static final Pattern ID = Pattern.compile("^f-\\d{3,}$");
    static final Pattern SCOPE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    public Fact {
        checkId(id);
        Turn.checkUserId(userId);
        if (kind == null) {
            throw new MemoryException(ErrorCode.VAL_002,
                    "invalid kind \"null\", valid: preference, constraint, background");
        }
        if (scope == null || scope.length() > MAX_SCOPE || !SCOPE.matcher(scope).matches()) {
            throw new MemoryException(ErrorCode.VAL_003,
                    "invalid scope \"" + scope + "\": lowercase latin words joined by \"-\"");
        }
        checkText(ErrorCode.VAL_004, "fact", text, forgottenAt);
        checkText(ErrorCode.VAL_005, "quote", quote, forgottenAt);
        if (standing == null) {
            throw new MemoryException(ErrorCode.VAL_007, "invalid standing \"null\", valid: stated, inferred");
        }
        require(sourceTurnId != null && !sourceTurnId.isBlank(), "fact " + id + " has no source turn");
        require(sourceSessionId != null && !sourceSessionId.isBlank(), "fact " + id + " has no source session");
        require(createdAt != null, "fact " + id + " has no created_at");
        if (supersedes != null) {
            checkId(supersedes);
        }
        require(status != null, "fact " + id + " has no status");
        require(status != FactStatus.FORGOTTEN || forgottenAt != null, "forgotten fact " + id + " has no date");
    }

    /** Новый действующий факт из реплики хода; цитата сверяется с {@code userText} (I1). */
    public static Fact create(String id, Turn source, FactKind kind, String scope, String text, String quote,
            Standing standing, String supersedes, Instant now) {
        Fact fact = new Fact(id, source.userId(), kind, scope, text, quote, standing, source.id(),
                source.sessionId(), now, supersedes, FactStatus.ACTIVE, null, null);
        if (!QuoteMatcher.contains(source.userText(), quote)) {
            throw new MemoryException(ErrorCode.VAL_006, "quote not found in user message of turn " + source.id());
        }
        return fact;
    }

    /** На теме уже есть действующий факт — {@code MEM-STATE-004} (I3). */
    public void checkScopeFree(Optional<Fact> active) {
        active.filter(other -> !other.id().equals(id)).ifPresent(other -> {
            throw new MemoryException(ErrorCode.STATE_004,
                    "scope \"" + scope + "\" already has active fact " + other.id());
        });
    }

    /** Этот факт заменён фактом {@code next} (I4): копия со статусом {@code superseded}. */
    public Fact supersededBy(Fact next) {
        if (!next.userId().equals(userId)) {
            throw new MemoryException(ErrorCode.STATE_002, "fact " + id + " belongs to another user");
        }
        if (status != FactStatus.ACTIVE) {
            throw new MemoryException(ErrorCode.STATE_001, "fact " + id + " is " + status.value());
        }
        if (!next.scope().equals(scope)) {
            throw new MemoryException(ErrorCode.STATE_003,
                    "scope \"" + next.scope() + "\" differs from target " + id + " scope \"" + scope + "\"");
        }
        if (next.standing() == Standing.INFERRED && standing == Standing.STATED) {
            throw new MemoryException(ErrorCode.STATE_005, "inferred fact cannot replace stated " + id);
        }
        require(id.equals(next.supersedes()), "fact " + next.id() + " does not reference " + id);
        return withStatus(FactStatus.SUPERSEDED, text, quote, forgottenAt, forgottenIn);
    }

    /** «Забудь» по id (I7): текст и цитата стёрты, след остаётся. */
    public Fact forget(String requestUserId, String sessionId, Instant now) {
        if (!userId.equals(requestUserId)) {
            throw new MemoryException(ErrorCode.STATE_002, "fact " + id + " belongs to another user");
        }
        if (status != FactStatus.ACTIVE) {
            throw new MemoryException(ErrorCode.STATE_001, "fact " + id + " is " + status.value());
        }
        return withStatus(FactStatus.FORGOTTEN, "", "", now, sessionId);
    }

    /** Стирание прежней версии в цепочке «забудь»; статус не меняется, повторный вызов ничего не делает. */
    public Fact erase(Instant now) {
        if (forgottenAt != null) {
            return this;
        }
        if (status == FactStatus.ACTIVE) {
            throw new IllegalStateException("active fact " + id + " is erased only by forget");
        }
        return withStatus(status, "", "", now, forgottenIn);
    }

    public boolean recallable() {
        return status == FactStatus.ACTIVE && standing == Standing.STATED;
    }

    /** Строка recall: {@code f-012 | constraint | вчера | Не носит юбки | «я не ношу юбки»}. */
    public String recallLine(Instant now) {
        return String.join(" | ", id, kind.value(), age(now), text, "«" + shortQuote() + "»");
    }

    private String age(Instant now) {
        long days = ChronoUnit.DAYS.between(utcDate(createdAt), utcDate(now));
        if (days <= 0) {
            return "сегодня";
        }
        if (days == 1) {
            return "вчера";
        }
        return days + " дней назад";
    }

    private String shortQuote() {
        if (quote.length() <= MAX_RECALL_QUOTE) {
            return quote;
        }
        String head = quote.substring(0, MAX_RECALL_QUOTE);
        int space = head.lastIndexOf(' ');
        if (space > 0) {
            head = head.substring(0, space);
        }
        return head.stripTrailing() + "…";
    }

    private static LocalDate utcDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private Fact withStatus(FactStatus next, String nextText, String nextQuote, Instant nextForgottenAt,
            String nextForgottenIn) {
        return new Fact(id, userId, kind, scope, nextText, nextQuote, standing, sourceTurnId, sourceSessionId,
                createdAt, supersedes, next, nextForgottenAt, nextForgottenIn);
    }

    private static void checkId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new MemoryException(ErrorCode.VAL_009, "invalid fact id \"" + id + "\"");
        }
    }

    private static void checkText(ErrorCode code, String field, String value, Instant forgottenAt) {
        if (forgottenAt != null) {
            require("".equals(value), "erased fact keeps " + field);
        } else if (value == null || value.isBlank() || value.length() > MAX_TEXT) {
            throw new MemoryException(code, field + " must be 1..200 chars");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
