package com.nekoyu.Universe.API;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlaceHolder {
    static Map<String, String> replacements = new HashMap<>();
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%([^%]+)%");

    public static void setReplacement(String key, String value) {
        replacements.put(key, value);
    }

    public static String getReplacement(String key) {
        return replacements.get(key);
    }

    public static String replace(String text) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String placeholder = matcher.group(1); // 提取占位符名称（去掉%）
            String replacement = replacements.get(placeholder);

            // 处理未识别的占位符（保留原文本）
            if (replacement == null) {
                replacement = matcher.group(0); // 使用原始占位符文本
            }

            // 转义特殊字符后替换
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
