package org.example.wardrobe.memory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.example.wardrobe.memory.application.Extractor;
import org.example.wardrobe.memory.application.ExtractorDecision;
import org.example.wardrobe.memory.domain.TurnRepository;
import org.example.wardrobe.memory.domain.TurnStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class MemoryControllerTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("MEMORY_DB_PATH", () -> dir.resolve("memory.db").toString());
    }

    @MockitoBean
    Extractor extractor;

    @Autowired
    WebApplicationContext context;

    @Autowired
    TurnRepository turns;

    private MockMvc mvc;
    private String user;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        user = "u-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String openTurn(String userText) throws Exception {
        String externalId = "ext-" + UUID.randomUUID();
        postJson("/turns", """
                {"user_id":"%s","session_id":"s-1","external_id":"%s","user_text":"%s"}"""
                .formatted(user, externalId, userText))
                .andExpect(status().isCreated());
        return externalId;
    }

    private void rememberSkirts() throws Exception {
        openTurn("Запомни: я не ношу юбки");
        postJson("/facts", """
                {"user_id":"%s","kind":"constraint","scope":"skirts","fact":"Не носит юбки","quote":"я не ношу юбки"}"""
                .formatted(user))
                .andExpect(status().isCreated());
    }

    private void awaitStatus(String externalId, TurnStatus expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (turns.findByExternalId(externalId).orElseThrow().status() != expected) {
            assertThat(Instant.now()).as("ход %s не стал %s", externalId, expected).isBefore(deadline);
            Thread.sleep(20);
        }
    }

    @Test
    void openTurn() throws Exception {
        String first = openTurn("первая реплика");
        String turnId = turns.findByExternalId(first).orElseThrow().id();

        postJson("/turns", """
                {"user_id":"%s","session_id":"s-1","external_id":"ext-%s","user_text":"вторая реплика"}"""
                .formatted(user, UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turn_id").isString())
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.abandoned[0]").value(turnId));
    }

    @Test
    void openTurnErrors() throws Exception {
        String externalId = openTurn("реплика");

        postJson("/turns", """
                {"user_id":"%s","session_id":"s-1","external_id":"%s","user_text":"повтор"}"""
                .formatted(user, externalId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEM-STATE-008"))
                .andExpect(jsonPath("$.error").value("turn " + externalId + " already exists"));
        postJson("/turns", """
                {"user_id":"%s","external_id":"ext-x","user_text":"реплика"}""".formatted(user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEM-VAL-011"));
        postJson("/turns", """
                {"user_id":"bad user","session_id":"s-1","external_id":"ext-y","user_text":"реплика"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEM-VAL-001"));
    }

    @Test
    void closeReturnsBeforeExtraction() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        when(extractor.extract(any())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });
        String externalId = openTurn("я не ношу юбки");

        postJson("/turns/close", """
                {"user_id":"%s","external_id":"%s","assistant_text":"Понял."}""".formatted(user, externalId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("pending"));

        assertThat(release.getCount()).as("ответ пришёл, пока экстрактор заблокирован").isEqualTo(1);
        assertThat(started.await(5, TimeUnit.SECONDS)).as("экстракция идёт в фоне").isTrue();
        assertThat(turns.findByExternalId(externalId).orElseThrow().status()).isEqualTo(TurnStatus.PENDING);
        release.countDown();
        awaitStatus(externalId, TurnStatus.DONE);
    }

    @Test
    void closeErrors() throws Exception {
        postJson("/turns/close", """
                {"user_id":"%s","external_id":"ext-404","assistant_text":"ответ"}""".formatted(user))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEM-NF-002"));
        postJson("/turns/close", """
                {"user_id":"%s","assistant_text":"ответ"}""".formatted(user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEM-VAL-011"));
    }

    @Test
    void rememberAndList() throws Exception {
        rememberSkirts();

        mvc.perform(get("/facts").param("user_id", user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts[0].id").isString())
                .andExpect(jsonPath("$.facts[0].kind").value("constraint"))
                .andExpect(jsonPath("$.facts[0].scope").value("skirts"))
                .andExpect(jsonPath("$.facts[0].fact").value("Не носит юбки"))
                .andExpect(jsonPath("$.facts[0].quote").value("я не ношу юбки"))
                .andExpect(jsonPath("$.facts[0].standing").value("stated"))
                .andExpect(jsonPath("$.facts[0].status").value("active"))
                .andExpect(jsonPath("$.facts[0].source_session_id").value("s-1"))
                .andExpect(jsonPath("$.facts[0].created_at").isString())
                .andExpect(jsonPath("$.facts[0].supersedes").isEmpty());
    }

    @Test
    void rememberErrors() throws Exception {
        String body = """
                {"user_id":"%s","kind":"constraint","scope":"skirts","fact":"Не носит юбки","quote":"я не ношу юбки"}"""
                .formatted(user);
        postJson("/facts", body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEM-STATE-006"));
        openTurn("Запомни, что я не люблю юбки");
        postJson("/facts", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEM-VAL-006"));
        postJson("/facts", body.replace("\"skirts\"", "\"Skirts!\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEM-VAL-003"));
        postJson("/facts", body.replace("}", ",\"supersedes\":\"f-999\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEM-NF-001"));
    }

    @Test
    void forget() throws Exception {
        rememberSkirts();
        String id = mvc.perform(get("/facts").param("user_id", user)).andReturn().getResponse()
                .getContentAsString().replaceAll("(?s).*\"id\":\"(f-\\d+)\".*", "$1");
        openTurn("Забудь, что я не ношу юбки");

        postJson("/facts/" + id + "/forget", "{\"user_id\":\"%s\"}".formatted(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("forgotten"))
                .andExpect(jsonPath("$.forgotten_at").isString())
                .andExpect(jsonPath("$.forgotten_in").value("s-1"));
        postJson("/facts/" + id + "/forget", "{\"user_id\":\"%s\"}".formatted(user))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEM-STATE-001"));
        postJson("/facts/f-999/forget", "{\"user_id\":\"%s\"}".formatted(user))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEM-NF-001"));
        postJson("/facts/skirts/forget", "{\"user_id\":\"%s\"}".formatted(user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEM-VAL-009"));
        mvc.perform(get("/facts").param("user_id", user))
                .andExpect(jsonPath("$.facts").isEmpty());
    }

    @Test
    void recall() throws Exception {
        rememberSkirts();

        postJson("/recall", """
                {"user_id":"%s","q":"что надеть вместо юбки?"}""".formatted(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context").value(org.hamcrest.Matchers.startsWith("Память о пользователе")))
                .andExpect(jsonPath("$.context").value(org.hamcrest.Matchers.containsString(
                        "| constraint | сегодня | Не носит юбки | «я не ношу юбки»")))
                .andExpect(jsonPath("$.ids.length()").value(1));
    }

    @Test
    void recallLimitZeroIsEmpty() throws Exception {
        rememberSkirts();

        postJson("/recall", """
                {"user_id":"%s","q":"юбки","limit":0}""".formatted(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context").value(""))
                .andExpect(jsonPath("$.ids").isEmpty());
    }

    @Test
    void listShowsInferred() throws Exception {
        when(extractor.extract(any())).thenReturn(List.of(new ExtractorDecision("ADD", "", "preference",
                "color-blue", "Любит синий", "опять синее платье", "inferred")));
        String externalId = openTurn("Хочу опять синее платье");
        postJson("/turns/close", """
                {"user_id":"%s","external_id":"%s","assistant_text":"Синее — отличный выбор."}"""
                .formatted(user, externalId))
                .andExpect(status().isAccepted());
        awaitStatus(externalId, TurnStatus.DONE);

        mvc.perform(get("/facts").param("user_id", user))
                .andExpect(jsonPath("$.facts[0].scope").value("color-blue"))
                .andExpect(jsonPath("$.facts[0].standing").value("inferred"));
        postJson("/recall", """
                {"user_id":"%s","q":"синее платье"}""".formatted(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context").value(""))
                .andExpect(jsonPath("$.ids").isEmpty());
    }

    @Test
    void requestErrors() throws Exception {
        postJson("/recall", "{not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEM-VAL-015"))
                .andExpect(jsonPath("$.error").value("request is malformed: body is not valid JSON"));
        postJson("/recall", """
                {"user_id":"%s","q":"юбки","limit":"много"}""".formatted(user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEM-VAL-015"))
                .andExpect(jsonPath("$.error").value("request is malformed: limit"));
        postJson("/recall", """
                {"user_id":"%s","q":"юбки","limit":-1}""".formatted(user))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEM-VAL-010"));
        postJson("/recall", """
                {"user_id":"","q":"юбки"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEM-VAL-001"));
        mvc.perform(get("/facts"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEM-VAL-011"))
                .andExpect(jsonPath("$.error").value("user_id is required"));
    }
}
