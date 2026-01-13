package com.nekoyu.Universe.API;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlaceHolder {
    static Map<String, String> replacements = new HashMap<>(){{put("%%", "%");}};
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%([^%]+)%");

    public static void setReplacement(String key, String value) {
        replacements.put(key, value);
    }

    public static String getReplacement(String key) {
        return replacements.get(key);
    }

    public static String replace(String text) {
        return replace(text, null);
    }

    public static String replace(String text, Map<String, String> localReplacements) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = null;

            if (localReplacements != null) {
                replacement = localReplacements.get(placeholder);
            }

            if (replacement == null) {
                replacement = replacements.get(placeholder);
            }

            // 仍然没找到 → 保留原占位符
            if (replacement == null) {
                replacement = matcher.group(0);
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(result);
        return result.toString();
    }
}
