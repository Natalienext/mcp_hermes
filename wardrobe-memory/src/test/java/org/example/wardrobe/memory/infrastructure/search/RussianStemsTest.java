package org.example.wardrobe.memory.infrastructure.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class RussianStemsTest {

    @Test
    void allSkirtForms() {
        List<String> stems = Stream.of("юбка", "юбки", "юбку", "юбкой", "юбками")
                .map(RussianStems::index)
                .distinct()
                .toList();

        assertThat(stems).containsExactly("юбк");
    }

    @Test
    void dropsStopWordsAndShortWords() {
        assertThat(RussianStems.index("я не ношу юбки")).isEqualTo("нош юбк");
        assertThat(RussianStems.index("и в на по")).isEmpty();
    }

    @Test
    void yoBecomesYe() {
        assertThat(RussianStems.index("Зелёный")).isEqualTo(RussianStems.index("зеленый"));
    }

    @Test
    void queryJoinsDistinctStemsWithOr() {
        assertThat(RussianStems.query("Юбку или юбки? Подбери юбку!"))
                .isEqualTo("\"юбк\" OR \"подбер\"");
    }

    @Test
    void emptyQuery() {
        assertThat(RussianStems.query("и я не")).isEmpty();
        assertThat(RussianStems.query("")).isEmpty();
        assertThat(RussianStems.query(null)).isEmpty();
    }
}
