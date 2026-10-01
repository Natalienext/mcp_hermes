package org.example.wardrobe.mcp.client;

/**
 * Ошибка обращения к wardrobe-api. Сообщение предназначено агенту: по нему он решает, что делать дальше.
 */
public class WardrobeApiException extends RuntimeException {

    public WardrobeApiException(String message) {
        super(message);
    }
}
