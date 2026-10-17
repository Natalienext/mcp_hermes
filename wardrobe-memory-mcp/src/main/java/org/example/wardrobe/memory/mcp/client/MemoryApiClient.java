package org.example.wardrobe.memory.mcp.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class MemoryApiClient {

    private static final Logger log = LoggerFactory.getLogger(MemoryApiClient.class);

    private final RestClient restClient;
    private final String userId;

    public MemoryApiClient(RestClient.Builder builder,
                           @Value("${memory.url}") String memoryUrl,
                           @Value("${memory.user-id}") String userId) {
        this.restClient = builder.baseUrl(memoryUrl).build();
        this.userId = userId;
    }

    /** POST /facts. Возвращает JSON-ответ сервиса как есть ({@code FactView}). */
    public String remember(String fact, String quote, String kind, String scope, String supersedes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", userId);
        body.put("kind", kind);
        body.put("scope", scope);
        body.put("fact", fact);
        body.put("quote", quote);
        if (supersedes != null && !supersedes.isBlank()) {
            body.put("supersedes", supersedes);
        }
        log.info("POST /facts scope={} supersedes={}", scope, supersedes);
        return call(() -> restClient.post().uri("/facts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
    }

    /** POST /facts/{id}/forget. */
    public String forget(String id) {
        log.info("POST /facts/{}/forget", id);
        return call(() -> restClient.post().uri("/facts/{id}/forget", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("user_id", userId))
                .retrieve()
                .body(String.class));
    }

    /** GET /facts — действующие факты, {@code stated} и {@code inferred}. */
    public String list() {
        log.info("GET /facts");
        return call(() -> restClient.get().uri(uri -> uri.path("/facts").queryParam("user_id", userId).build())
                .retrieve()
                .body(String.class));
    }

    private static String call(Supplier<String> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            // Ответ сервиса {"code","error"} передаём агенту кодом и сообщением, чтобы он исправил вызов
            ApiError error = errorOf(e);
            log.warn("Memory service rejected request: status={} code={}", e.getStatusCode().value(), error.code());
            throw new MemoryApiException(error.code() + ": " + error.error());
        } catch (ResourceAccessException e) {
            log.error("Memory service is unavailable", e);
            throw new MemoryApiException("Сервис памяти недоступен");
        }
    }

    private static ApiError errorOf(RestClientResponseException e) {
        try {
            ApiError error = e.getResponseBodyAs(ApiError.class);
            if (error != null && error.code() != null) {
                return error;
            }
        } catch (RuntimeException ignored) {
            // тело не JSON ошибки сервиса — ниже ответ по HTTP-статусу
        }
        return new ApiError("MEM-INT-001", "HTTP " + e.getStatusCode().value());
    }

    private record ApiError(String code, String error) {
    }
}
