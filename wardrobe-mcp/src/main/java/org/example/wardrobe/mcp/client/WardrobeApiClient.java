package org.example.wardrobe.mcp.client;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

@Component
public class WardrobeApiClient {

    private static final Logger log = LoggerFactory.getLogger(WardrobeApiClient.class);

    private final RestClient restClient;

    public WardrobeApiClient(RestClient.Builder builder, @Value("${wardrobe.api-url}") String apiUrl) {
        this.restClient = builder.baseUrl(apiUrl).build();
    }

    /**
     * GET /api/items. Возвращает JSON-ответ API как есть: он уже минимальный ({items: [{id, description}]}).
     */
    public String searchItems(List<String> excludeType, List<String> excludeColor, List<String> excludeSeason) {
        log.info("GET /api/items excludeType={} excludeColor={} excludeSeason={}", excludeType, excludeColor, excludeSeason);
        try {
            return restClient.get()
                    .uri(uri -> {
                        UriBuilder builder = uri.path("/api/items");
                        addParam(builder, "excludeType", excludeType);
                        addParam(builder, "excludeColor", excludeColor);
                        addParam(builder, "excludeSeason", excludeSeason);
                        return builder.build();
                    })
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException e) {
            // 400 от API содержит список допустимых значений — передаём агенту, чтобы он исправил вызов
            log.warn("API rejected request: {}", e.getResponseBodyAsString());
            throw new WardrobeApiException(e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            log.error("Wardrobe API is unavailable", e);
            throw new WardrobeApiException("Сервис гардероба недоступен, попробуй позже");
        }
    }

    private static void addParam(UriBuilder builder, String name, List<String> values) {
        if (!values.isEmpty()) {
            builder.queryParam(name, String.join(",", values));
        }
    }
}
