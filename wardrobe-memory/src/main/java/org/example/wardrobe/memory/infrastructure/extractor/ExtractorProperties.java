package org.example.wardrobe.memory.infrastructure.extractor;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Модель экстрактора (Р11): {@code EXTRACTOR_BASE_URL}, {@code EXTRACTOR_MODEL}, {@code EXTRACTOR_API_KEY},
 * {@code EXTRACTOR_TIMEOUT}. Пустой ключ — запрос без {@code Authorization} (локальная Ollama).
 */
@ConfigurationProperties(prefix = "extractor")
public record ExtractorProperties(
        @DefaultValue("https://openrouter.ai/api/v1") String baseUrl,
        @DefaultValue("deepseek/deepseek-v4.1-flash") String model,
        @DefaultValue("") String apiKey,
        @DefaultValue("30s") Duration timeout) {

    @Override
    public String toString() {
        return "ExtractorProperties[baseUrl=" + baseUrl + ", model=" + model + ", timeout=" + timeout + "]";
    }
}
