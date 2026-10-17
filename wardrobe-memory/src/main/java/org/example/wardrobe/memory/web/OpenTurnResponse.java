package org.example.wardrobe.memory.web;

import java.util.List;

public record OpenTurnResponse(String turnId, String status, List<String> abandoned) {
}
