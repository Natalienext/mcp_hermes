package org.example.wardrobe.memory.mcp.tool;

import java.util.function.Supplier;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.example.wardrobe.memory.mcp.client.MemoryApiClient;
import org.example.wardrobe.memory.mcp.client.MemoryApiException;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class MemoryTools {

    private final MemoryApiClient apiClient;

    public MemoryTools(MemoryApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @McpTool(
            name = "memory_remember",
            description = """
                    Записывает устойчивый факт о пользователе в долгую память: предпочтение, ограничение \
                    или сведение о себе.
                    Когда использовать: только по прямой просьбе пользователя запомнить («запомни, что…»). \
                    quote — дословный фрагмент текущей реплики пользователя, сервис сверит его с репликой. \
                    Тема занята действующим фактом — передай его id в supersedes, чтобы заменить.
                    Когда НЕ использовать: без просьбы запомнить, для своих догадок и для переменного \
                    (погода, планы на сегодня, настроение).""",
            annotations = @McpTool.McpAnnotations(
                    title = "Запомнить факт",
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = false))
    public CallToolResult memoryRemember(
            @McpToolParam(description = "Факт коротко, от третьего лица: «Не носит юбки»") String fact,
            @McpToolParam(description = "Дословная цитата из текущей реплики пользователя") String quote,
            @McpToolParam(description = "Род факта: preference, constraint (запрет) или background")
            String kind,
            @McpToolParam(description = "Тема — ключ латиницей через дефис: skirts, color-red")
            String scope,
            @McpToolParam(required = false, description = "id заменяемого факта той же темы, f-NNN")
            String supersedes) {
        return result(() -> apiClient.remember(fact, quote, kind, scope, supersedes));
    }

    @McpTool(
            name = "memory_forget",
            description = """
                    Забывает факт о пользователе: текст стирается вместе с цепочкой прежних версий.
                    Когда использовать: пользователь просит забыть то, что о нём известно. \
                    id бери из строк памяти во входе, иначе из memory_list.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Забыть факт",
                    readOnlyHint = false,
                    destructiveHint = true,
                    idempotentHint = false,
                    openWorldHint = false))
    public CallToolResult memoryForget(
            @McpToolParam(description = "id факта, f-NNN") String id) {
        return result(() -> apiClient.forget(id));
    }

    @McpTool(
            name = "memory_list",
            description = """
                    Возвращает действующие факты о пользователе. standing=stated — сказано пользователем, \
                    standing=inferred — догадка модели.
                    Когда использовать: пользователь спрашивает, что о нём известно, или нужен id для \
                    memory_forget / supersedes, которого нет во входе.""",
            annotations = @McpTool.McpAnnotations(
                    title = "Память о пользователе",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public CallToolResult memoryList() {
        return result(apiClient::list);
    }

    private static CallToolResult result(Supplier<String> call) {
        try {
            return CallToolResult.builder().addTextContent(call.get()).build();
        } catch (MemoryApiException e) {
            // Результат с isError=true формируем сами: так агент получает ровно наше сообщение
            return CallToolResult.builder().isError(true).addTextContent(e.getMessage()).build();
        }
    }
}
