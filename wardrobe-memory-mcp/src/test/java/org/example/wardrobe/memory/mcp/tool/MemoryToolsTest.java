package org.example.wardrobe.memory.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.example.wardrobe.memory.mcp.client.MemoryApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MemoryToolsTest {

    private static final String BASE = "http://memory.test";
    private static final String FACT = """
            {"id":"f-001","kind":"constraint","scope":"skirts","fact":"Не носит юбки","quote":"я не ношу юбки",\
            "standing":"stated","status":"active"}""";

    private MockRestServiceServer server;
    private MemoryTools tools;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tools = new MemoryTools(new MemoryApiClient(builder, BASE, "me"));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @Test
    void rememberReturnsServiceJsonAsText() {
        server.expect(requestTo(BASE + "/facts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.user_id").value("me"))
                .andExpect(jsonPath("$.kind").value("constraint"))
                .andExpect(jsonPath("$.scope").value("skirts"))
                .andExpect(jsonPath("$.fact").value("Не носит юбки"))
                .andExpect(jsonPath("$.quote").value("я не ношу юбки"))
                .andExpect(jsonPath("$.supersedes").doesNotExist())
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(FACT));

        CallToolResult result = tools.memoryRemember("Не носит юбки", "я не ношу юбки", "constraint", "skirts", null);

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).isEqualTo(FACT);
        server.verify();
    }

    @Test
    void rememberPassesSupersedes() {
        server.expect(requestTo(BASE + "/facts"))
                .andExpect(jsonPath("$.supersedes").value("f-001"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(FACT));

        tools.memoryRemember("Носит юбки", "ношу юбки", "preference", "skirts", "f-001");

        server.verify();
    }

    @Test
    void conflictIsErrorWithCode() {
        server.expect(requestTo(BASE + "/facts"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("""
                        {"code":"MEM-STATE-006",\
                        "error":"no open turn for user me: remember/forget work only inside a turn"}"""));

        CallToolResult result = tools.memoryRemember("Не носит юбки", "я не ношу юбки", "constraint", "skirts", null);

        assertThat(result.isError()).isTrue();
        assertThat(text(result))
                .isEqualTo("MEM-STATE-006: no open turn for user me: remember/forget work only inside a turn");
    }

    @Test
    void badRequestIsErrorWithCode() {
        server.expect(requestTo(BASE + "/facts/abc/forget"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"MEM-VAL-009\",\"error\":\"invalid fact id \\\"abc\\\"\"}"));

        CallToolResult result = tools.memoryForget("abc");

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("MEM-VAL-009: invalid fact id \"abc\"");
    }

    @Test
    void serviceDownIsError() {
        server.expect(requestTo(BASE + "/facts?user_id=me"))
                .andRespond(withException(new ConnectException("Connection refused")));

        CallToolResult result = tools.memoryList();

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("Сервис памяти недоступен");
    }

    @Test
    void forgetSendsUserId() {
        String forgotten = "{\"id\":\"f-001\",\"status\":\"forgotten\"}";
        server.expect(requestTo(BASE + "/facts/f-001/forget"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"user_id\":\"me\"}"))
                .andRespond(withSuccess(forgotten, MediaType.APPLICATION_JSON));

        CallToolResult result = tools.memoryForget("f-001");

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).isEqualTo(forgotten);
    }

    @Test
    void listShowsStanding() {
        String facts = "{\"facts\":[" + FACT + ",{\"id\":\"f-002\",\"standing\":\"inferred\"}]}";
        server.expect(requestTo(BASE + "/facts?user_id=me"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(facts, MediaType.APPLICATION_JSON));

        CallToolResult result = tools.memoryList();

        assertThat(text(result)).isEqualTo(facts).contains("\"standing\":\"inferred\"");
    }
}
