package com.nekoyu.Universe.AIChat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 调用方解析研究与合并结果，避免正文和多行摘要依赖分隔符。 */
record LibrarianResult(String answer, List<LibrarianResult.Source> sources) {
    private static final Gson GSON = new Gson();

    LibrarianResult {
        sources = List.copyOf(sources);
    }

    record Source(String url, String title, String content) {}

    static LibrarianResult withoutSources(String answer) {
        return new LibrarianResult(answer, List.of());
    }

    String toJson() {
        return GSON.toJson(this);
    }

    static LibrarianResult parse(String output) {
        try {
            JsonObject root = object(output, Set.of("answer", "sources"));
            String answer = string(root, "answer");
            if (answer.isBlank() || !root.get("sources").isJsonArray())
                throw new IllegalArgumentException("Expected an answer and sources array");
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
            return new LibrarianResult(answer, sources);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid research result JSON", e);
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
