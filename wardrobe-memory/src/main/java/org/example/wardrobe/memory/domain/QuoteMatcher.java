package org.example.wardrobe.memory.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Цитата есть в реплике (I1): без учёта регистра, {@code ё/е}, пунктуации и лишних пробелов.
 */
public final class QuoteMatcher {

    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private QuoteMatcher() {
    }

    public static boolean contains(String text, String quote) {
        String normalizedQuote = normalize(quote);
        return !normalizedQuote.isEmpty() && normalize(text).contains(normalizedQuote);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT).replace('ё', 'е');
        return NOT_WORD.matcher(lower).replaceAll(" ").trim();
    }
}
