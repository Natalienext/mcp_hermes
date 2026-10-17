package org.example.wardrobe.memory.mcp.client;

/**
 * Ошибка обращения к wardrobe-memory. Сообщение предназначено агенту: {@code MEM-…: сообщение}
 * или «Сервис памяти недоступен».
 */
public class MemoryApiException extends RuntimeException {

    public MemoryApiException(String message) {
        super(message);
    }
}
