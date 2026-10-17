package org.example.wardrobe.memory.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HookTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CONTEXT = "Память о пользователе (id | род | возраст | факт | «цитата»).\n"
            + "f-001 | constraint | сегодня | Не носит юбки | «я не ношу юбки»";
    private static final String PRE = """
            {"hook_event_name":"pre_llm_call","session_id":"s-1","cwd":"/tmp","extra":{"turn_id":"s-1:t:ab12cd34",\
            "user_message":"Что надеть вместо юбки?","conversation_history":[],"is_first_turn":true}}""";
    private static final String POST = """
            {"hook_event_name":"post_llm_call","session_id":"s-1","extra":{"turn_id":"s-1:t:ab12cd34",\
            "user_message":"Что надеть вместо юбки?","assistant_response":"Джинсы и рубашку."}}""";

    /** Фейк сервиса: ответы по пути, запросы записываются. */
    private HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private volatile long delayMs;

    private record Request(String path, JsonNode body) {
    }

    private record Response(int status, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(new Request(path, JSON.readTree(exchange.getRequestBody().readAllBytes())));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Response response = responses.getOrDefault(path, new Response(404, "{\"code\":\"MEM-NF-002\"}"));
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        responses.put("/recall", new Response(200, JSON.writeValueAsString(
                Map.of("context", CONTEXT, "ids", List.of("f-001")))));
        responses.put("/turns", new Response(201, "{\"turn_id\":\"t-001\",\"status\":\"open\",\"abandoned\":[]}"));
        responses.put("/turns/close", new Response(202, "{\"turn_id\":\"t-001\",\"status\":\"pending\"}"));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private Map<String, String> env(long timeoutMs) {
        return Map.of("MEMORY_URL", "http://127.0.0.1:" + server.getAddress().getPort(),
                "MEMORY_USER_ID", "me", "MEMORY_HOOK_TIMEOUT_MS", String.valueOf(timeoutMs));
    }

    private record Run(int code, String stdout, long millis) {
    }

    private static Run run(String stdin, Map<String, String> env) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long start = System.nanoTime();
        int code = new Hook().run(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), out, env);
        return new Run(code, out.toString(StandardCharsets.UTF_8), (System.nanoTime() - start) / 1_000_000);
    }

    private List<String> paths() {
        return requests.stream().map(Request::path).toList();
    }

    private JsonNode body(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).findFirst().orElseThrow().body();
    }

    @Test
    void preRecallsOpensTurnAndPrintsContext() {
        Run run = run(PRE, env(1000));

        assertThat(run.code()).isZero();
        assertThat(JSON.readTree(run.stdout()).path("context").asString()).isEqualTo(CONTEXT);
        assertThat(JSON.readTree(run.stdout()).size()).isEqualTo(1);
        assertThat(paths()).containsExactly("/recall", "/turns");
        assertThat(body("/recall").path("user_id").asString()).isEqualTo("me");
        assertThat(body("/recall").path("q").asString()).isEqualTo("Что надеть вместо юбки?");
        JsonNode open = body("/turns");
        assertThat(open.path("user_id").asString()).isEqualTo("me");
        assertThat(open.path("session_id").asString()).isEqualTo("s-1");
        assertThat(open.path("external_id").asString()).isEqualTo("s-1:t:ab12cd34");
        assertThat(open.path("user_text").asString()).isEqualTo("Что надеть вместо юбки?");
    }

    @Test
    void emptyContextPrintsNothing() {
        responses.put("/recall", new Response(200, "{\"context\":\"\",\"ids\":[]}"));

        Run run = run(PRE, env(1000));

        assertThat(run.code()).isZero();
        assertThat(run.stdout()).isEmpty();
        assertThat(paths()).containsExactly("/recall", "/turns");
    }

    @Test
    void recallFailsTurnStillOpens() {
        responses.put("/recall", new Response(500, "{\"code\":\"MEM-INT-001\",\"error\":\"internal error\"}"));

        Run run = run(PRE, env(1000));

        assertThat(run.code()).isZero();
        assertThat(run.stdout()).isEmpty();
        assertThat(paths()).containsExactly("/recall", "/turns");
    }

    @Test
    void openFailsContextStillPrinted() {
        responses.put("/turns", new Response(409, "{\"code\":\"MEM-STATE-008\",\"error\":\"exists\"}"));

        Run run = run(PRE, env(1000));

        assertThat(run.code()).isZero();
        assertThat(JSON.readTree(run.stdout()).path("context").asString()).isEqualTo(CONTEXT);
    }

    @Test
    void postClosesTurn() {
        Run run = run(POST, env(1000));

        assertThat(run.code()).isZero();
        assertThat(run.stdout()).isEmpty();
        assertThat(paths()).containsExactly("/turns/close");
        JsonNode close = body("/turns/close");
        assertThat(close.path("user_id").asString()).isEqualTo("me");
        assertThat(close.path("external_id").asString()).isEqualTo("s-1:t:ab12cd34");
        assertThat(close.path("assistant_text").asString()).isEqualTo("Джинсы и рубашку.");
    }

    @Test
    void serviceDown() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        Run run = run(PRE, Map.of("MEMORY_URL", "http://127.0.0.1:" + closedPort, "MEMORY_HOOK_TIMEOUT_MS", "1000"));

        assertThat(run.code()).isZero();
        assertThat(run.stdout()).isEmpty();
        assertThat(run.millis()).isLessThan(2500);
    }

    @Test
    void serviceSlow() {
        delayMs = 3000;

        Run pre = run(PRE, env(300));
        Run post = run(POST, env(300));

        assertThat(pre.code()).isZero();
        assertThat(pre.stdout()).isEmpty();
        // pre — два запроса, у каждого свой таймаут
        assertThat(pre.millis()).isLessThan(2 * 300 + 500);
        assertThat(post.code()).isZero();
        assertThat(post.millis()).isLessThan(300 + 500);
    }

    @Test
    void badJson() {
        Run run = run("{not json", env(1000));

        assertThat(run.code()).isZero();
        assertThat(run.stdout()).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void userMessageNotStringOpensNothing() {
        String parts = """
                {"hook_event_name":"pre_llm_call","session_id":"s-1","extra":{"turn_id":"s-1:t:1",\
                "user_message":[{"type":"text","text":"Что надеть?"},{"type":"image_url"}]}}""";

        Run run = run(parts, env(1000));

        assertThat(run.code()).isZero();
        assertThat(run.stdout()).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void otherEventDoesNothing() {
        Run run = run("{\"hook_event_name\":\"on_session_start\",\"session_id\":\"s-1\",\"extra\":{}}", env(1000));

        assertThat(run.code()).isZero();
        assertThat(run.stdout()).isEmpty();
        assertThat(requests).isEmpty();
    }
}
