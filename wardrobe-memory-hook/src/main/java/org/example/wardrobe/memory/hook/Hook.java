package org.example.wardrobe.memory.hook;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Мост Hermes → wardrobe-memory. pre: recall и открытие хода — независимо (D10); post: закрытие хода.
 * Любая ошибка — пустой stdout и код 0 (I12): хук не ломает ход. В stderr — только событие, класс ошибки и код.
 */
public final class Hook {

    static final String DEFAULT_URL = "http://127.0.0.1:8091";
    static final String DEFAULT_USER_ID = "me";
    static final long DEFAULT_TIMEOUT_MS = 1000;

    private final ObjectMapper mapper = JsonMapper.builder().build();

    public int run(InputStream stdin, OutputStream stdout, Map<String, String> env) {
        try {
            HookPayload payload = HookPayload.parse(mapper, new String(stdin.readAllBytes(), StandardCharsets.UTF_8));
            MemoryClient client = new MemoryClient(mapper, env.getOrDefault("MEMORY_URL", DEFAULT_URL),
                    Duration.ofMillis(timeoutMs(env)));
            String userId = env.getOrDefault("MEMORY_USER_ID", DEFAULT_USER_ID);
            if (HookPayload.PRE.equals(payload.event())) {
                pre(client, userId, payload, stdout);
            } else if (HookPayload.POST.equals(payload.event())) {
                post(client, userId, payload);
            }
        } catch (Throwable e) {
            report("hook", e);
        }
        return 0;
    }

    private void pre(MemoryClient client, String userId, HookPayload payload, OutputStream stdout) throws Exception {
        if (payload.userMessage() == null) {
            return;
        }
        String context = "";
        try {
            JsonNode recall = client.post("/recall", Map.of("user_id", userId, "q", payload.userMessage()));
            context = recall.path("context").asString("");
        } catch (Throwable e) {
            report("recall", e);
        }
        try {
            client.post("/turns", Map.of(
                    "user_id", userId,
                    "session_id", nonNull(payload.sessionId()),
                    "external_id", nonNull(payload.turnId()),
                    "user_text", payload.userMessage()));
        } catch (Throwable e) {
            report("open", e);
        }
        if (!context.isBlank()) {
            stdout.write(mapper.writeValueAsBytes(Map.of("context", context)));
        }
    }

    private static void post(MemoryClient client, String userId, HookPayload payload) throws Exception {
        if (payload.turnId() == null || payload.assistantResponse() == null) {
            return;
        }
        client.post("/turns/close", Map.of(
                "user_id", userId,
                "external_id", payload.turnId(),
                "assistant_text", payload.assistantResponse()));
    }

    private static long timeoutMs(Map<String, String> env) {
        String value = env.get("MEMORY_HOOK_TIMEOUT_MS");
        return value == null || value.isBlank() ? DEFAULT_TIMEOUT_MS : Long.parseLong(value.trim());
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    /** Без текста реплики и ответа (I9): только шаг, класс ошибки и статус/код сервиса. */
    private static void report(String step, Throwable e) {
        PrintStream err = System.err;
        if (e instanceof ExecutionException && e.getCause() != null) {
            e = e.getCause();
        }
        String detail = e instanceof IllegalStateException ? ": " + e.getMessage() : "";
        err.println("wardrobe-memory-hook " + step + " failed " + e.getClass().getSimpleName() + detail);
    }
}
