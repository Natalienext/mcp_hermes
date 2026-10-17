package org.example.wardrobe.memory.domain;

import java.time.Instant;
import java.util.List;

/**
 * Блок памяти для входа модели. Пустой результат — {@code ""} без заголовка (AC-14): ветка по строкам, не по лимиту.
 */
public record RecallBlock(String context, List<String> ids) {

    public static final String HEADER = "Память о пользователе (id | род | возраст | факт | «цитата»). "
            + "constraint — запрет: передай его в исключения wardrobe_search.";

    public static RecallBlock render(List<Fact> facts, Instant now) {
        List<String> lines = facts.stream().map(fact -> fact.recallLine(now)).toList();
        List<String> ids = facts.stream().map(Fact::id).toList();
        if (lines.isEmpty()) {
            return new RecallBlock("", ids);
        }
        return new RecallBlock(HEADER + "\n" + String.join("\n", lines), ids);
    }
}
