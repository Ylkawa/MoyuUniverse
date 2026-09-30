package com.nekoyu.Universe.AIChat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The validated, side-effect-free result of the leading model call. */
final class LeadingPlan {
    static final int MAX_QUERIES_PER_STORE = 2;
    private static final Set<String> FIELDS = Set.of("add", "update", "delete", "memory_queries", "knowledge_queries");
    final List<NewMemory> additions = new ArrayList<>();
    final List<UpdatedMemory> updates = new ArrayList<>();
    final List<Integer> deletions = new ArrayList<>();
    final List<MemoryQuery> memoryQueries = new ArrayList<>();
    final List<String> knowledgeQueries = new ArrayList<>();

    record NewMemory(String locationId, String content, float confidence, float importance) {}
    record UpdatedMemory(int id, String content, float confidence, float importance) {}
    record MemoryQuery(String question, String locationId) {}

    static LeadingPlan parse(String output, Set<String> allowedLocations, Set<Integer> knownMemoryIds) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(output);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Expected JSON object");
            root = parsed.getAsJsonObject();
            if (!root.keySet().equals(FIELDS)) throw new IllegalArgumentException("Leading JSON fields do not match contract");
            for (String field : FIELDS) {
                if (!root.get(field).isJsonArray()) throw new IllegalArgumentException("Expected array: " + field);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid leading JSON", e);
        }

        LeadingPlan plan = new LeadingPlan();
        Set<Integer> touchedIds = new HashSet<>();
        Set<String> addedFacts = new HashSet<>();
        for (JsonElement element : array(root, "add")) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            String location = string(value, "location_id", 160);
            String content = string(value, "content", 500);
            Float confidence = fraction(value, "confidence");
            Float importance = fraction(value, "importance");
            if (allowedLocations.contains(location) && content != null && confidence != null && importance != null
                    && addedFacts.add(location + "\n" + content)
                    && plan.additions.size() < 3) {
                plan.additions.add(new NewMemory(location, content, confidence, importance));
            }
        }
        for (JsonElement element : array(root, "update")) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            Integer id = positiveInteger(value, "id");
            String content = string(value, "content", 500);
            Float confidence = fraction(value, "confidence");
            Float importance = fraction(value, "importance");
            if (id != null && knownMemoryIds.contains(id) && !touchedIds.contains(id) && content != null
                    && confidence != null && importance != null && plan.updates.size() < 3) {
                plan.updates.add(new UpdatedMemory(id, content, confidence, importance));
                touchedIds.add(id);
            }
        }
        for (JsonElement element : array(root, "delete")) {
            Integer id = positiveInteger(element);
            if (id != null && knownMemoryIds.contains(id) && touchedIds.add(id) && plan.deletions.size() < 3) {
                plan.deletions.add(id);
            }
        }
        Set<String> seenMemoryQueries = new HashSet<>();
        for (JsonElement element : array(root, "memory_queries")) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            String question = string(value, "question", 200);
            String location = string(value, "location_id", 160);
            JsonElement rawLocation = value.get("location_id");
            if (question == null || (rawLocation != null && !rawLocation.isJsonNull() && location == null)
                    || (location != null && !allowedLocations.contains(location))) continue;
            if (seenMemoryQueries.add(question) && plan.memoryQueries.size() < MAX_QUERIES_PER_STORE) {
                plan.memoryQueries.add(new MemoryQuery(question, location));
            }
        }
        Set<String> seenKnowledgeQueries = new HashSet<>();
        for (JsonElement element : array(root, "knowledge_queries")) {
            String question = string(element, 200);
            if (question != null && seenKnowledgeQueries.add(question)
                    && plan.knowledgeQueries.size() < MAX_QUERIES_PER_STORE) {
                plan.knowledgeQueries.add(question);
            }
        }
        return plan;
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject object, String key, int maxLength) {
        return string(object.get(key), maxLength);
    }

    private static String string(JsonElement element, int maxLength) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return null;
        String value = element.getAsString().strip();
        return value.isEmpty() || value.length() > maxLength ? null : value;
    }

    private static Float fraction(JsonObject object, String key) {
        JsonElement element = object.get(key);
        try {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return null;
            float value = element.getAsFloat();
            return Float.isFinite(value) && value >= 0 && value <= 1 ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer positiveInteger(JsonObject object, String key) {
        return positiveInteger(object.get(key));
    }

    private static Integer positiveInteger(JsonElement element) {
        try {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return null;
            int value = element.getAsInt();
            return value > 0 && value == element.getAsDouble() ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
