package org.example.wardrobe.memory.domain;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Ход: реплика пользователя от pre до разбора экстрактором. Переходы — только методами, иначе {@code MEM-STATE-007}.
 */
public record Turn(
        String id,
        String externalId,
        String userId,
        String sessionId,
        String userText,
        String assistantText,
        TurnStatus status,
        String failReason,
        boolean forgetRequested,
        Instant createdAt,
        Instant erasedAt) {

    public static final int MAX_TEXT = 20_000;
    static final Pattern ID = Pattern.compile("^t-\\d{3,}$");
    static final Pattern USER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public Turn {
        require(id != null && ID.matcher(id).matches(), "invalid turn id \"" + id + "\"");
        if (externalId == null || externalId.isBlank()) {
            throw new MemoryException(ErrorCode.VAL_011, "external_id is required");
        }
        checkUserId(userId);
        if (sessionId == null || sessionId.isBlank()) {
            throw new MemoryException(ErrorCode.VAL_011, "session_id is required");
        }
        if (erasedAt == null) {
            checkText("user_text", userText, 1);
        } else {
            require("".equals(userText), "erased turn " + id + " keeps user_text");
        }
        checkText("assistant_text", assistantText, 0);
        require(status != null, "turn " + id + " has no status");
        require(status == TurnStatus.PENDING || assistantText.isEmpty(),
                "turn " + id + " is " + status.value() + " and keeps assistant_text");
        require(failReason != null && (status == TurnStatus.FAILED) == !failReason.isBlank(),
                "turn " + id + " fail_reason does not match status " + status.value());
        require(createdAt != null, "turn " + id + " has no created_at");
    }

    public static Turn open(String id, String externalId, String userId, String sessionId, String userText,
            Instant now) {
        return new Turn(id, externalId, userId, sessionId, userText, "", TurnStatus.OPEN, "", false, now, null);
    }

    public Turn close(String assistantText) {
        expect(TurnStatus.OPEN);
        return withStatus(TurnStatus.PENDING, assistantText, "");
    }

    public Turn abandon() {
        expect(TurnStatus.OPEN);
        return withStatus(TurnStatus.ABANDONED, "", "");
    }

    public Turn markDone() {
        expect(TurnStatus.PENDING);
        return withStatus(TurnStatus.DONE, "", "");
    }

    public Turn markFailed(String reason) {
        expect(TurnStatus.PENDING);
        return withStatus(TurnStatus.FAILED, "", reason);
    }

    public Turn requestForget() {
        expect(TurnStatus.OPEN);
        return new Turn(id, externalId, userId, sessionId, userText, assistantText, status, failReason, true,
                createdAt, erasedAt);
    }

    public Turn eraseUserText(Instant now) {
        if (erasedAt != null) {
            return this;
        }
        return new Turn(id, externalId, userId, sessionId, "", assistantText, status, failReason, forgetRequested,
                createdAt, now);
    }

    public boolean needsExtraction() {
        return status == TurnStatus.PENDING && !forgetRequested;
    }

    static void checkUserId(String userId) {
        if (userId == null || !USER_ID.matcher(userId).matches()) {
            throw new MemoryException(ErrorCode.VAL_001, "user_id is invalid");
        }
    }

    private Turn withStatus(TurnStatus next, String nextAssistantText, String nextFailReason) {
        return new Turn(id, externalId, userId, sessionId, userText, nextAssistantText, next, nextFailReason,
                forgetRequested, createdAt, erasedAt);
    }

    private void expect(TurnStatus expected) {
        if (status != expected) {
            throw new MemoryException(ErrorCode.STATE_007, "turn " + id + " is " + status.value());
        }
    }

    private static void checkText(String field, String text, int min) {
        if (text == null || text.length() < min || text.length() > MAX_TEXT) {
            throw new MemoryException(ErrorCode.VAL_008, field + " must be " + min + "..20000 chars");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
