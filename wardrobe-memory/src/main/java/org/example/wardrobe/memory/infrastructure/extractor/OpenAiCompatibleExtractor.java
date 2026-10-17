package org.example.wardrobe.memory.infrastructure.extractor;

import java.util.ArrayList;
import java.util.List;

import org.example.wardrobe.memory.application.ExtractionFailedException;
import org.example.wardrobe.memory.application.ExtractionInput;
import org.example.wardrobe.memory.application.Extractor;
import org.example.wardrobe.memory.application.ExtractorDecision;
import org.example.wardrobe.memory.domain.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Экстрактор через OpenAI-совместимый {@code /chat/completions}: {@code temperature=0}, {@code json_object},
 * одна попытка. Ключ и тексты хода в лог не пишутся (I9).
 */
@Component
public class OpenAiCompatibleExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleExtractor.class);
    private static final List<String> FIELDS =
            List.of("op", "target_id", "kind", "scope", "fact", "quote", "standing");

    private final RestClient restClient;
    private final String model;
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    public OpenAiCompatibleExtractor(RestClient.Builder builder, ExtractorProperties properties) {
        this(restClient(builder.requestFactory(timeoutFactory(properties)), properties), properties.model());
    }

    OpenAiCompatibleExtractor(RestClient restClient, String model) {
        this.restClient = restClient;
        this.model = model;
    }

    /** Клиент к {@code EXTRACTOR_BASE_URL}; фабрику запросов задаёт вызывающий (в тестах — {@code MockRestServiceServer}). */
    static RestClient restClient(RestClient.Builder builder, ExtractorProperties properties) {
        builder.baseUrl(properties.baseUrl());
        if (!properties.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
        }
        return builder.build();
    }

    @Override
    public List<ExtractorDecision> extract(ExtractionInput input) {
        String response = call(requestBody(input));
        JsonNode decisions = decisions(content(response));
        List<ExtractorDecision> result = new ArrayList<>();
        for (int i = 0; i < decisions.size(); i++) {
            JsonNode element = decisions.get(i);
            String problem = problem(element);
            if (problem != null) {
                log.warn("extract reject element={} code={} cause={}", i, ErrorCode.EXT_002.code(), problem);
                continue;
            }
            result.add(new ExtractorDecision(element.get("op").stringValue(), element.get("target_id").stringValue(),
                    element.get("kind").stringValue(), element.get("scope").stringValue(),
                    element.get("fact").stringValue(), element.get("quote").stringValue(),
                    element.get("standing").stringValue()));
        }
        return result;
    }

    private String call(String body) {
        try {
            return restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new ExtractionFailedException(ErrorCode.EXT_001, "extractor unavailable: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + firstLine(e.getMessage())));
        }
    }

    private String requestBody(ExtractionInput input) {
        ObjectNode user = json.createObjectNode();
        user.put("user_text", input.userText());
        user.put("assistant_text", input.assistantText());
        ArrayNode candidates = user.putArray("candidates");
        for (ExtractionInput.Candidate candidate : input.candidates()) {
            candidates.addObject()
                    .put("id", candidate.id())
                    .put("kind", candidate.kind())
                    .put("scope", candidate.scope())
                    .put("fact", candidate.fact())
                    .put("standing", candidate.standing());
        }
        ObjectNode request = json.createObjectNode();
        request.put("model", model);
        request.put("temperature", 0);
        request.putObject("response_format").put("type", "json_object");
        ArrayNode messages = request.putArray("messages");
        messages.addObject().put("role", "system").put("content", ExtractorPrompt.SYSTEM);
        messages.addObject().put("role", "user").put("content", json.writeValueAsString(user));
        return json.writeValueAsString(request);
    }

    private String content(String response) {
        try {
            JsonNode content = json.readTree(response == null ? "" : response)
                    .path("choices").path(0).path("message").path("content");
            if (!content.isString()) {
                throw invalid("no choices[0].message.content");
            }
            return content.stringValue();
        } catch (JacksonException e) {
            throw invalid("completion is not JSON");
        }
    }

    private JsonNode decisions(String content) {
        try {
            JsonNode decisions = json.readTree(unwrap(content)).path("decisions");
            if (!decisions.isArray()) {
                throw invalid("no decisions array");
            }
            return decisions;
        } catch (JacksonException e) {
            throw invalid("content is not JSON");
        }
    }

    /** Снимает обёртку {@code ```json … ```}, если модель её добавила. */
    static String unwrap(String content) {
        String text = content.strip();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            text = firstLineEnd < 0 ? "" : text.substring(firstLineEnd + 1);
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.strip();
    }

    private static String problem(JsonNode element) {
        if (!element.isObject()) {
            return "element is not an object";
        }
        for (String field : FIELDS) {
            if (!element.path(field).isString()) {
                return "field " + field + " is not a string";
            }
        }
        return null;
    }

    private static ExtractionFailedException invalid(String cause) {
        return new ExtractionFailedException(ErrorCode.EXT_002, "extractor response invalid: " + cause);
    }

    private static String firstLine(String message) {
        int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }

    private static SimpleClientHttpRequestFactory timeoutFactory(ExtractorProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        return factory;
    }
}
