package org.example.wardrobe.memory.web;

public record RecallRequest(String userId, String q, Integer limit) {
}
