package org.example.wardrobe.memory.web;

/** Ошибка API: {@code {"code": "MEM-…", "error": "<сообщение>"}}. */
public record ApiError(String code, String error) {
}
