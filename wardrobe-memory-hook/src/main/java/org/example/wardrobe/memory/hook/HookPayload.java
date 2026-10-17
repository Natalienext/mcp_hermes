package org.example.wardrobe.memory.hook;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Нужные хуку поля stdin Hermes: {@code hook_event_name}, {@code session_id}, {@code extra.turn_id},
 * {@code extra.user_message}, {@code extra.assistant_response}. Поле, которое не строка, — {@code null}:
 * {@code user_message} списком частей (картинки) хук не разбирает (D5).
 */
record HookPayload(String event, String sessionId, String turnId, String userMessage, String assistantResponse) {

    static final String PRE = "pre_llm_call";
    static final String POST = "post_llm_call";

    /** Битый JSON или не объект — исключение Jackson; его ловит {@link Hook#run}. */
    static HookPayload parse(ObjectMapper mapper, String json) {
        JsonNode root = mapper.readTree(json);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("payload is not a JSON object");
        }
        JsonNode extra = root.path("extra");
        return new HookPayload(text(root.path("hook_event_name")), text(root.path("session_id")),
                text(extra.path("turn_id")), text(extra.path("user_message")),
                text(extra.path("assistant_response")));
    }

    private static String text(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }
}
