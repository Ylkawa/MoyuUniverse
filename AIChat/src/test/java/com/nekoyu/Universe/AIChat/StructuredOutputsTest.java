package com.nekoyu.Universe.AIChat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;

import java.util.List;
import java.util.Set;

/** Offline output-contract and parser acceptance checks. */
public class StructuredOutputsTest {
    public static void main(String[] args) {
        Gson gson = new Gson();
        for (var format : List.of(StructuredOutputs.leading(), StructuredOutputs.memory(),
                StructuredOutputs.knowledge(), StructuredOutputs.research(), StructuredOutputs.conflict())) {
            JsonObject wire = gson.toJsonTree(format).getAsJsonObject();
            check(wire.get("type").getAsString().equals("json_schema"), "schema mode is explicit");
            check(wire.getAsJsonObject("json_schema").get("strict").getAsBoolean(), "strict mode is explicit");
            checkClosedObjects(wire.getAsJsonObject("json_schema").get("schema"));
        }
        JsonObject location = gson.toJsonTree(StructuredOutputs.leading()).getAsJsonObject()
                .getAsJsonObject("json_schema").getAsJsonObject("schema").getAsJsonObject("properties")
                .getAsJsonObject("memory_queries").getAsJsonObject("items").getAsJsonObject("properties")
                .getAsJsonObject("location_id");
        check(location.getAsJsonArray("anyOf").size() == 2
                && location.getAsJsonArray("anyOf").get(1).getAsJsonObject().get("type").getAsString().equals("null"),
                "nullable location is declared in the wire schema");

        String research = """
                {"answer":"产品 A 的答案\\n第二段", "sources":[
                  {"url":"https://example.com/a","title":"资料 A","content":"第一行\\n\\nURL: 这只是摘要正文\\n最后一行"}]}
                """;
        LibrarianResult result = LibrarianResult.parse(research);
        check(result.answer().contains("\n第二段") && result.sources().get(0).content().endsWith("最后一行"),
                "keep multiline answer and full source summary");
        check(LibrarianResult.parse(result.toJson()).equals(result), "preserve sources between research and merge");
        check(LibrarianResult.parse("{\"answer\":\"无来源\",\"sources\":[]}").sources().isEmpty(), "allow no sources");
        check(LibrarianResult.parseConflict("{\"conflict\":true}"), "parse true conflict");
        check(!LibrarianResult.parseConflict("{\"conflict\":false}"), "parse false conflict");
        rejects(() -> LibrarianResult.parseConflict("{\"conflict\":\"YES\"}"));
        rejects(() -> LibrarianResult.parseConflict("YES"));
        rejects(() -> LibrarianResult.parseConflict("{\"conflict\":false,\"explanation\":\"YES appears here\"}"));
        rejects(() -> LibrarianResult.parse("{\"answer\":\"答案\",\"sources\":[{\"url\":\"x\"}]}"));
        rejects(() -> LibrarianResult.parse("答案\n===SOURCES===\nURL: https://example.com\n===END==="));
        rejects(() -> LibrarianResult.parse("{\"answer\":\"答案\",\"sources\":null}"));

        CompletionsResponse response = new CompletionsResponse();
        response.choices = new CompletionsResponse.Choice[]{new CompletionsResponse.Choice()};
        response.choices[0].message.content = research;
        response.choices[0].finish_reason = "stop";
        check(StructuredOutputs.content(response).equals(research), "return complete raw JSON");
        response.choices[0].finish_reason = "length";
        rejects(() -> StructuredOutputs.content(response));
        response.choices[0].finish_reason = "tool_calls";
        rejects(() -> StructuredOutputs.content(response));
        rejects(() -> StructuredOutputs.content(null));
        System.out.println("StructuredOutputsTest passed");
    }

    private static void checkClosedObjects(JsonElement schema) {
        JsonObject object = schema.getAsJsonObject();
        if (object.has("properties")) {
            check(object.get("additionalProperties").getAsBoolean() == false, "disallow additional fields");
            Set<String> fields = object.getAsJsonObject("properties").keySet();
            Set<String> required = new java.util.HashSet<>();
            object.getAsJsonArray("required").forEach(field -> required.add(field.getAsString()));
            check(required.equals(fields), "require every declared output field");
            object.getAsJsonObject("properties").entrySet().forEach(entry -> checkClosedObjects(entry.getValue()));
        }
        if (object.has("items")) checkClosedObjects(object.get("items"));
        if (object.has("anyOf")) object.getAsJsonArray("anyOf").forEach(StructuredOutputsTest::checkClosedObjects);
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
            throw new AssertionError("invalid output was accepted");
        } catch (IllegalArgumentException expected) {
            // Invalid responses cannot be used as structured business results.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
