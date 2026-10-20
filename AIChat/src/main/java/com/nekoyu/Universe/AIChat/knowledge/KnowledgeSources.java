package com.nekoyu.Universe.AIChat.knowledge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 后台入库使用的来源与 JSON 校验；自然语言研究不调用这些解析器。 */
record KnowledgeSources(List<KnowledgeSources.Source> sources) {
    private static final Gson GSON = new Gson();

    KnowledgeSources {
        sources = List.copyOf(sources);
    }

    record Source(String url, String title, String content) {}

    String toJson() {
        return GSON.toJson(this);
    }

    static KnowledgeSources parse(String output) {
        try {
            JsonObject root = object(output, Set.of("sources"));
            if (!root.get("sources").isJsonArray())
                throw new IllegalArgumentException("Expected sources array");
            List<Source> sources = new ArrayList<>();
            for (var element : root.getAsJsonArray("sources")) {
                if (!element.isJsonObject()) throw new IllegalArgumentException("Expected source object");
                JsonObject source = element.getAsJsonObject();
                if (!source.keySet().equals(Set.of("url", "title", "content")))
                    throw new IllegalArgumentException("Source fields do not match contract");
                String url = string(source, "url").strip();
                if (url.isEmpty()) throw new IllegalArgumentException("Source URL is empty");
                sources.add(new Source(url, string(source, "title"), string(source, "content")));
            }
            return new KnowledgeSources(sources);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid knowledge sources JSON", e);
        }
    }

    static boolean parseConflict(String output) {
        try {
            JsonObject root = object(output, Set.of("conflict"));
            var value = root.get("conflict");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException("Expected boolean conflict");
            return value.getAsBoolean();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid conflict JSON", e);
        }
    }

    static List<Source> parseSources(String output) {
        return parse(output).sources();
    }

    private static JsonObject object(String output, Set<String> fields) {
        var value = JsonParser.parseString(output);
        if (!value.isJsonObject() || !value.getAsJsonObject().keySet().equals(fields))
            throw new IllegalArgumentException("Output fields do not match contract");
        return value.getAsJsonObject();
    }

    private static String string(JsonObject object, String name) {
        var value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected string: " + name);
        return value.getAsString();
    }
}
