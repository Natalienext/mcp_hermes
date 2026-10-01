package org.example.wardrobe.mcp.tool;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.example.wardrobe.mcp.client.WardrobeApiClient;
import org.example.wardrobe.mcp.client.WardrobeApiException;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class WardrobeSearchTool {

    private final WardrobeApiClient apiClient;

    public WardrobeSearchTool(WardrobeApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @McpTool(
            name = "wardrobe_search",
            description = """
                    Возвращает вещи из реального гардероба пользователя в виде {id, description}. \
                    Фильтры только ИСКЛЮЧАЮТ вещи, а не отбирают их.
                    Когда использовать: чтобы получить список реальной одежды пользователя перед составлением образа. \
                    Явные запреты пользователя передавай как исключения: «без юбок» → excludeType=[SKIRT], \
                    «ничего красного» → excludeColor=[RED]. В тёплую погоду исключай WINTER, в холодную — SUMMER; \
                    если не уверен — сезон не исключай.
                    Когда НЕ использовать: для поиска вещей с желаемым признаком («что-нибудь синее») — \
                    вызови без фильтров и выбирай сам. Никогда не предлагай вещи, которых нет в результате.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Поиск по гардеробу",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult wardrobeSearch(
            @McpToolParam(required = false,
                    description = "Типы вещей, которые исключить (пользователь явно не хочет их надевать)")
            List<ItemType> excludeType,
            @McpToolParam(required = false,
                    description = "Цвета, которые исключить. Вещь исключается, если в ней есть хотя бы один из этих цветов")
            List<Color> excludeColor,
            @McpToolParam(required = false,
                    description = "Категорически сезонные вещи, которые не подходят по погоде")
            List<Season> excludeSeason) {
        try {
            String items = apiClient.searchItems(values(excludeType), values(excludeColor), values(excludeSeason));
            return CallToolResult.builder().addTextContent(items).build();
        } catch (WardrobeApiException e) {
            // Результат с isError=true формируем сами: так агент получает ровно наше сообщение
            return CallToolResult.builder().isError(true).addTextContent(e.getMessage()).build();
        }
    }

    private static List<String> values(List<? extends Enum<?>> constants) {
        return constants == null ? List.of() : constants.stream()
                .map(Enum::name)
                .toList();
    }
}
