package org.example.wardrobe.memory.web;

public record RememberRequest(String userId, String kind, String scope, String fact, String quote, String supersedes) {
}
