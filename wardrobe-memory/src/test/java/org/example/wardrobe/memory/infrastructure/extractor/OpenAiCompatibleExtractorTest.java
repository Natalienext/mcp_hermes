package org.example.wardrobe.memory.infrastructure.extractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import org.example.wardrobe.memory.application.ExtractionFailedException;
import org.example.wardrobe.memory.application.ExtractionInput;
import org.example.wardrobe.memory.application.ExtractorDecision;
import org.example.wardrobe.memory.domain.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleExtractorTest {

    private static final String URL = "http://model.test/v1/chat/completions";
    private static final ExtractionInput INPUT = new ExtractionInput("я не ношу юбки", "Понял.",
            List.of(new ExtractionInput.Candidate("f-001", "preference", "color-blue", "Любит синий", "stated")));
    private static final String SKIRTS = """
            {"op":"ADD","target_id":"","kind":"constraint","scope":"skirts","fact":"Не носит юбки",\
            "quote":"я не ношу юбки","standing":"stated"}""";

    private MockRestServiceServer server;

    private OpenAiCompatibleExtractor extractor(String apiKey) {
        ExtractorProperties properties =
                new ExtractorProperties("http://model.test/v1", "test-model", apiKey, Duration.ofSeconds(1));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new OpenAiCompatibleExtractor(OpenAiCompatibleExtractor.restClient(builder, properties),
                properties.model());
    }

    private static String completion(String content) {
        String quoted = JsonMapper.builder().build().writeValueAsString(content);
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + quoted + "}}]}";
    }

    @Test
    void validJson() {
        OpenAiCompatibleExtractor extractor = extractor("");
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.temperature").value(0))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value(ExtractorPrompt.SYSTEM))
                .andExpect(jsonPath("$.messages[1].content").value(
                        "{\"user_text\":\"я не ношу юбки\",\"assistant_text\":\"Понял.\",\"candidates\":"
                                + "[{\"id\":\"f-001\",\"kind\":\"preference\",\"scope\":\"color-blue\","
                                + "\"fact\":\"Любит синий\",\"standing\":\"stated\"}]}"))
                .andRespond(withSuccess(completion("{\"decisions\":[" + SKIRTS + "]}"), MediaType.APPLICATION_JSON));

        List<ExtractorDecision> decisions = extractor.extract(INPUT);

        assertThat(decisions).containsExactly(new ExtractorDecision("ADD", "", "constraint", "skirts",
                "Не носит юбки", "я не ношу юбки", "stated"));
        server.verify();
    }

    @Test
    void emptyDecisions() {
        OpenAiCompatibleExtractor extractor = extractor("");
        server.expect(requestTo(URL))
                .andRespond(withSuccess(completion("{\"decisions\":[]}"), MediaType.APPLICATION_JSON));

        assertThat(extractor.extract(INPUT)).isEmpty();
    }

    @Test
    void markdownWrapperIsRemoved() {
        OpenAiCompatibleExtractor extractor = extractor("");
        server.expect(requestTo(URL)).andRespond(withSuccess(
                completion("```json\n{\"decisions\":[" + SKIRTS + "]}\n```"), MediaType.APPLICATION_JSON));

        assertThat(extractor.extract(INPUT)).extracting(ExtractorDecision::scope).containsExactly("skirts");
    }

    @Test
    void contentNotJsonIsExt002() {
        OpenAiCompatibleExtractor extractor = extractor("");
        server.expect(requestTo(URL))
                .andRespond(withSuccess(completion("Пользователь не носит юбки."), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> extractor.extract(INPUT))
                .isInstanceOfSatisfying(ExtractionFailedException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.EXT_002));
    }

    @Test
    void noDecisionsArrayIsExt002() {
        OpenAiCompatibleExtractor extractor = extractor("");
        server.expect(requestTo(URL))
                .andRespond(withSuccess(completion("{\"facts\":[]}"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> extractor.extract(INPUT))
                .isInstanceOfSatisfying(ExtractionFailedException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.EXT_002));
    }

    @Test
    void completionWithoutContentIsExt002() {
        OpenAiCompatibleExtractor extractor = extractor("");
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> extractor.extract(INPUT))
                .isInstanceOfSatisfying(ExtractionFailedException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.EXT_002));
    }

    @Test
    void serverErrorIsExt001() {
        OpenAiCompatibleExtractor extractor = extractor("");
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> extractor.extract(INPUT))
                .isInstanceOfSatisfying(ExtractionFailedException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.EXT_001));
        server.verify();
    }

    @Test
    void unauthorizedIsExt001() {
        OpenAiCompatibleExtractor extractor = extractor("wrong-key");
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> extractor.extract(INPUT))
                .isInstanceOfSatisfying(ExtractionFailedException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.EXT_001));
    }

    @Test
    void timeoutIsExt001() {
        OpenAiCompatibleExtractor extractor = extractor("");
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> extractor.extract(INPUT))
                .isInstanceOfSatisfying(ExtractionFailedException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.EXT_001));
    }

    @Test
    void malformedElementIsRejectedAlone() {
        OpenAiCompatibleExtractor extractor = extractor("");
        String content = "{\"decisions\":[" + SKIRTS + ", \"ADD skirts\", "
                + SKIRTS.replace("\"scope\":\"skirts\"", "\"scope\":42") + ", "
                + SKIRTS.replace(",\"standing\":\"stated\"", "") + ", "
                + SKIRTS.replace("skirts", "shorts") + "]}";
        server.expect(requestTo(URL)).andRespond(withSuccess(completion(content), MediaType.APPLICATION_JSON));

        assertThat(extractor.extract(INPUT)).extracting(ExtractorDecision::scope)
                .containsExactly("skirts", "shorts");
    }

    @Test
    void authorizationHeaderWithKey() {
        OpenAiCompatibleExtractor extractor = extractor("secret-key");
        server.expect(requestTo(URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret-key"))
                .andRespond(withSuccess(completion("{\"decisions\":[]}"), MediaType.APPLICATION_JSON));

        extractor.extract(INPUT);
        server.verify();
    }

    @Test
    void noAuthorizationHeaderWithoutKey() {
        OpenAiCompatibleExtractor extractor = extractor("");
        server.expect(requestTo(URL))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess(completion("{\"decisions\":[]}"), MediaType.APPLICATION_JSON));

        extractor.extract(INPUT);
        server.verify();
    }

    @Test
    void keyIsNotInPropertiesToString() {
        ExtractorProperties properties =
                new ExtractorProperties("http://model.test/v1", "test-model", "secret-key", Duration.ofSeconds(1));

        assertThat(properties.toString()).doesNotContain("secret-key");
    }
}
