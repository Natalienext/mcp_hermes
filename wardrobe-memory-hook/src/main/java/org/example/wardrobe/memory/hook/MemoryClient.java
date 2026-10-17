package org.example.wardrobe.memory.hook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** HTTP к wardrobe-memory на JDK {@link HttpClient}; таймаут — на каждый запрос целиком. */
final class MemoryClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final Duration timeout;

    MemoryClient(ObjectMapper mapper, String baseUrl, Duration timeout) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
    }

    /** POST JSON; не 2xx, сеть или таймаут — исключение. */
    JsonNode post(String path, Map<String, ?> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        CompletableFuture<HttpResponse<String>> call = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            response = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            call.cancel(true);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(path + " HTTP " + response.statusCode() + " " + codeOf(response.body()));
        }
        return mapper.readTree(response.body());
    }

    private String codeOf(String body) {
        try {
            return mapper.readTree(body).path("code").asString("");
        } catch (RuntimeException e) {
            return "";
        }
    }
}
