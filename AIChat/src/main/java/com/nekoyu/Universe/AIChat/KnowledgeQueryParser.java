package com.nekoyu.Universe.AIChat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Validates model-generated retrieval questions before they are embedded and indexed. */
final class KnowledgeQueryParser {
    private static final Set<Integer> TTL_DAYS = Set.of(7, 30, 90, 180, 365, 730, 3650);

    record Entry(String question, String conclusion, float confidence, int ttlDays) {}

    static List<Entry> parse(String output, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("Question limit must be positive");
        JsonArray items;
        try {
            JsonElement parsed = JsonParser.parseString(output);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Expected JSON object");
            JsonObject root = parsed.getAsJsonObject();
            if (!root.keySet().equals(Set.of("items")) || !root.get("items").isJsonArray()) {
                throw new IllegalArgumentException("Expected an items array");
            }
            items = root.getAsJsonArray("items");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid knowledge query JSON", e);
        }

        List<Entry> result = new ArrayList<>();
        Set<String> seenQuestions = new HashSet<>();
        for (JsonElement element : items) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (!item.keySet().equals(Set.of("question", "conclusion", "confidence", "ttl_days"))) continue;
            String question = string(item.get("question"), 300);
            String conclusion = string(item.get("conclusion"), 1000);
            Float confidence = confidence(item.get("confidence"));
            Integer ttlDays = ttlDays(item.get("ttl_days"));
            if (question == null || conclusion == null || confidence == null || ttlDays == null) continue;
            if (!seenQuestions.add(question.toLowerCase(Locale.ROOT))) continue;
            result.add(new Entry(question, conclusion, confidence, ttlDays));
            if (result.size() >= limit) break;
        }
        return result;
    }

    private static String string(JsonElement element, int maxLength) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return null;
        String value = element.getAsString().strip();
        return value.isEmpty() || value.length() > maxLength ? null : value;
    }

    private static Float confidence(JsonElement element) {
        try {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return null;
            float value = element.getAsFloat();
            return Float.isFinite(value) && value >= 0.8f && value <= 1f ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer ttlDays(JsonElement element) {
        try {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return null;
            int value = element.getAsInt();
            return value == element.getAsDouble() && TTL_DAYS.contains(value) ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
