package com.contacts.cleaner.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NameFormattingService {
    private static final Pattern NAME_WORD_TOKEN = Pattern.compile("[\\p{L}\\p{Nd}']+");

    private NameFormattingService() {
    }

    public static String stripAccents(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "");
    }

    public static List<String> dedupeNameTokens(List<String> parts) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String normalizedPart = stripAccents(part == null ? "" : part);
            List<String> kept = new ArrayList<>();
            Matcher m = NAME_WORD_TOKEN.matcher(normalizedPart);
            while (m.find()) {
                String word = stripAccents(m.group());
                String key = word.toLowerCase(Locale.ROOT);
                if (seen.add(key)) {
                    kept.add(word);
                }
            }
            if (!kept.isEmpty()) {
                result.add(String.join(" ", kept));
            }
        }
        return result;
    }

    public static void addIfNonBlank(List<String> parts, String[] values, int idx) {
        if (idx < 0 || idx >= values.length || values[idx] == null) {
            return;
        }
        String v = stripAccents(values[idx].trim());
        if (!v.isEmpty()) {
            parts.add(v);
        }
    }

    public static void clearIfPresent(String[] values, int idx) {
        if (idx >= 0 && idx < values.length) {
            values[idx] = "";
        }
    }
}
