package org.example.wardrobe.memory.infrastructure.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.tartarus.snowball.ext.RussianStemmer;

/**
 * Основы слов для FTS (D1): одна функция и для тела строки индекса, и для запроса recall.
 */
public final class RussianStems {

    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    private static final int MIN_LENGTH = 3;
    private static final CharArraySet STOP_WORDS = RussianAnalyzer.getDefaultStopSet();

    private RussianStems() {
    }

    /** Тело FTS-строки: основы через пробел. */
    public static String index(String text) {
        return String.join(" ", stems(text));
    }

    /** Выражение MATCH: основы через OR; пусто, если значимых слов нет. */
    public static String query(String text) {
        return new LinkedHashSet<>(stems(text)).stream()
                .map(stem -> '"' + stem + '"')
                .collect(Collectors.joining(" OR "));
    }

    static List<String> stems(String text) {
        if (text == null) {
            return List.of();
        }
        Matcher words = WORD.matcher(text.toLowerCase(Locale.ROOT).replace('ё', 'е'));
        RussianStemmer stemmer = new RussianStemmer();
        List<String> result = new ArrayList<>();
        while (words.find()) {
            String word = words.group();
            if (word.length() < MIN_LENGTH || STOP_WORDS.contains(word)) {
                continue;
            }
            stemmer.setCurrent(word);
            stemmer.stem();
            result.add(stemmer.getCurrent());
        }
        return result;
    }
}
