package org.example.wardrobe.memory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class RecallBlockTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    @Test
    void emptyResultIsEmptyContext() {
        RecallBlock block = RecallBlock.render(List.of(), NOW);

        assertThat(block.context()).isEmpty();
        assertThat(block.ids()).isEmpty();
    }

    @Test
    void headerAndLines() {
        Turn turn = Turn.open("t-001", "ext-1", "me", "s-1", "я не ношу юбки и люблю синий", NOW);
        Fact skirts = Fact.create("f-001", turn, FactKind.CONSTRAINT, "skirts", "Не носит юбки", "я не ношу юбки",
                Standing.STATED, null, NOW);
        Fact blue = Fact.create("f-002", turn, FactKind.PREFERENCE, "color-blue", "Любит синий", "люблю синий",
                Standing.STATED, null, NOW);

        RecallBlock block = RecallBlock.render(List.of(skirts, blue), NOW);

        assertThat(block.context()).isEqualTo("""
                Память о пользователе (id | род | возраст | факт | «цитата»). \
                constraint — запрет: передай его в исключения wardrobe_search.
                f-001 | constraint | сегодня | Не носит юбки | «я не ношу юбки»
                f-002 | preference | сегодня | Любит синий | «люблю синий»""");
        assertThat(block.ids()).containsExactly("f-001", "f-002");
    }
}
