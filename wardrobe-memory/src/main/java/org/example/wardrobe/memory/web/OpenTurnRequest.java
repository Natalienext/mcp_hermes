package org.example.wardrobe.memory.web;

public record OpenTurnRequest(String userId, String sessionId, String externalId, String userText) {
}
