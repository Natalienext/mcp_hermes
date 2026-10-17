package org.example.wardrobe.memory.web;

import java.util.List;

public record RecallResponse(String context, List<String> ids) {
}
