package com.nekoyu.Universe.AIChat;

import com.google.gson.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.Universe;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Offline only: fake provider outputs verify plumbing, not model judgement. */
class MailResearchPromptProbeTest {
    private static final String OUTPUT = """
            {"history_assessments":[],"mail_findings":[],"research_directions":[],"limitations":[]}
            """;
    @TempDir Path temporary;

    @Test void sendsProjectMessagesAndSchemaSeparatelyAndUsesFreshContextEachTime() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LLMProvider provider = provider((model, messages, tools, request) -> {
            calls.incrementAndGet();
            assertEquals("configured-model", model);
            assertTrue(request.enable_thinking);
            assertNull(tools);
            assertEquals(2, messages.size(), "No previous model answer in the next run");
            assertEquals("system", messages.get(0).role);
            assertEquals(MailResearchPromptProbe.PROMPT, messages.get(0).toString());
            assertFalse(messages.get(0).toString().contains("字段定义："), "Adapter owns schema instruction rendering");
            assertEquals("user", messages.get(1).role);
            JsonObject input = JsonParser.parseString(messages.get(1).toString()).getAsJsonObject();
            assertEquals("M1", input.getAsJsonObject("mail").get("id").getAsString());
            assertFalse(input.getAsJsonObject("daily_research").get("enabled").getAsBoolean());
            assertEquals("json_schema", request.responseFormat.type);
            assertTrue(request.responseFormat.jsonSchema.strict);
            assertEquals("mail_research_plan", request.responseFormat.jsonSchema.name);
            return response(OUTPUT, "stop");
        });
        for (int n = 0; n < 2; n++) {
            Path output = temporary.resolve("run" + n);
            assertEquals(4, MailResearchPromptProbe.run(provider, "configured-model", true, "base", output).size());
            JsonObject snapshot = read(output.resolve("request.json"));
            assertTrue(snapshot.getAsJsonObject("options").get("enable_thinking").getAsBoolean());
            assertTrue(snapshot.getAsJsonArray("tools").isEmpty());
            assertEquals(OUTPUT, Files.readString(output.resolve("model-output.txt")));
            assertEquals(JsonParser.parseString(OUTPUT), read(output.resolve("result.json")));
            assertFalse(Files.exists(output.resolve("failure.txt")));
        }
        assertEquals(2, calls.get());
    }

    @Test void variantsAreIndependentAndCarryTheirOwnReviewExpectations() throws Exception {
        JsonObject base = MailResearchPromptProbe.input("base");
        assertTrue(MailResearchPromptProbe.input("no-interest").getAsJsonArray("interests").isEmpty());
        JsonObject known = MailResearchPromptProbe.input("known-event");
        assertEquals(3, known.getAsJsonArray("baseline").size());
        assertTrue(known.getAsJsonArray("history").get(0).getAsJsonObject().get("summary").getAsString().contains("10月27日"));
        JsonObject other = MailResearchPromptProbe.input("different-platform");
        assertTrue(other.getAsJsonArray("baseline").isEmpty());
        assertTrue(other.getAsJsonObject("mail").get("subject").getAsString().contains("测试云"));
        assertTrue(other.getAsJsonArray("history").get(0).getAsJsonObject().get("topic").getAsString().contains("百炼"));
        assertTrue(other.getAsJsonObject("mail").getAsJsonArray("links").isEmpty());
        assertTrue(MailResearchPromptProbe.input("injection").getAsJsonObject("mail").toString().contains("P999"));
        assertEquals(base, MailResearchPromptProbe.input("base"), "Variants must not mutate the base fixture");
        for (String name : MailResearchPromptProbe.CASES) {
            Path output = temporary.resolve(name);
            MailResearchPromptProbe.preview(name, output);
            assertEquals(MailResearchPromptProbe.input(name), read(output.resolve("input.json")));
            assertFalse(Files.exists(output.resolve("response.json")));
            assertEquals(6, JsonParser.parseString(Files.readString(output.resolve("expectations.json"))).getAsJsonArray().size());
        }
        assertThrows(IllegalArgumentException.class, () -> MailResearchPromptProbe.input("typo"));
    }

    @Test void incompleteOutputIsRetainedButCannotBeAcceptedAsAResult() throws Exception {
        Path output = temporary.resolve("truncated");
        assertThrows(IllegalArgumentException.class, () -> MailResearchPromptProbe.run(
                provider((m, messages, t, r) -> response(OUTPUT, "length")), "offline", false, "base", output));
        assertEquals(OUTPUT, Files.readString(output.resolve("model-output.txt")));
        assertEquals("length", read(output.resolve("response.json")).get("finish_reason").getAsString());
        assertTrue(Files.readString(output.resolve("failure.txt")).contains("Structured output did not finish"));
        assertFalse(Files.exists(output.resolve("result.json")));
    }

    @Test void newsletterEvidenceRemainsInputDataWithItsHistoryAndPriceConditions() throws Exception {
        JsonObject fixture = MailResearchPromptProbe.input("newsletter");
        Path output = temporary.resolve("newsletter-context");
        AtomicInteger calls = new AtomicInteger();
        MailResearchPromptProbe.run(provider((model, messages, tools, request) -> {
            calls.incrementAndGet();
            assertNull(tools, "Planning must not fetch sources to fill missing evidence");
            assertEquals(2, messages.size());
            JsonObject received = JsonParser.parseString(messages.get(1).toString()).getAsJsonObject();
            assertEquals(fixture, received, "Do not pre-rewrite a publisher's misleading comparison into an accepted fact");
            assertTrue(received.getAsJsonObject("mail").getAsJsonArray("paragraphs").toString().contains("under 100K"));
            assertTrue(received.getAsJsonArray("history").get(1).toString().contains("10月27日"));
            assertTrue(received.getAsJsonArray("baseline").get(0).toString().contains("10月14日"));
            return response(OUTPUT, "stop");
        }), "offline", false, "newsletter", output);
        assertEquals(1, calls.get());
        assertEquals(fixture, read(output.resolve("input.json")));
        assertTrue(Files.readString(output.resolve("expectations.json")).contains("50%"));
        assertEquals(1, MailResearchPromptProbe.input("base").getAsJsonArray("baseline").size());
    }

    @Test void invalidJsonAndNonObjectResponsesRetainRawOutputAndFailure() throws Exception {
        for (String raw : List.of("{bad json", "[]")) {
            Path output = temporary.resolve(UUID.randomUUID().toString());
            assertThrows(RuntimeException.class, () -> MailResearchPromptProbe.run(
                    provider((m, messages, t, r) -> response(raw, "stop")), "offline", false, "base", output));
            assertEquals(raw, Files.readString(output.resolve("model-output.txt")));
            assertTrue(Files.exists(output.resolve("failure.txt")));
            assertFalse(Files.exists(output.resolve("result.json")));
        }
    }

    @Test void providerFailureStillLeavesReproducibleInputAndRequest() throws Exception {
        Path output = temporary.resolve("failed");
        assertThrows(IOException.class, () -> MailResearchPromptProbe.run(provider((m, messages, t, r) -> {
            throw new IOException("offline transport failure");
        }), "offline", false, "base", output));
        assertTrue(Files.exists(output.resolve("request.json")));
        assertTrue(Files.exists(output.resolve("input.json")));
        assertTrue(Files.readString(output.resolve("failure.txt")).contains("offline transport failure"));
        assertFalse(Files.exists(output.resolve("result.json")));
    }

    @Test void trialSchemaRequiresEveryDeclaredFieldAndDisallowsExtras() {
        verifySchema(MailResearchPromptProbe.probeFormat().jsonSchema.schema);
    }

    @Test void adapterSelectionRejectsMissingDuplicateAndNativeSearchBeforeLoading() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> MailResearchPromptProbe.checkAdapterDirectory(temporary, "chosen"));
        Path first = temporary.resolve("first.json");
        Files.writeString(first, "{\"ProviderId\":\"chosen\",\"Search\":false}");
        MailResearchPromptProbe.checkAdapterDirectory(temporary, "chosen");
        Path second = temporary.resolve("second.json");
        Files.writeString(second, "{\"ProviderId\":\"chosen\"}");
        assertThrows(IllegalArgumentException.class, () -> MailResearchPromptProbe.checkAdapterDirectory(temporary, "chosen"));
        Files.writeString(second, "{\"ProviderId\":\"other\",\"Search\":true}");
        MailResearchPromptProbe.checkAdapterDirectory(temporary, "chosen");
        Files.writeString(first, "{\"ProviderId\":\"chosen\",\"Search\":true}");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MailResearchPromptProbe.checkAdapterDirectory(temporary, "chosen")).getMessage().contains("Search=false"));
    }

    @Test void standaloneEntryLoadsAdapterAndProcessesSseWithoutStartingUniverse() throws Exception {
        AtomicReference<JsonObject> wire = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            wire.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            JsonObject delta = new JsonObject();
            delta.addProperty("content", OUTPUT);
            JsonObject choice = new JsonObject();
            choice.add("delta", delta);
            choice.addProperty("finish_reason", "stop");
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject event = new JsonObject();
            event.add("choices", choices);
            byte[] sse = ("data: " + event + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, sse.length);
            try (var body = exchange.getResponseBody()) { body.write(sse); }
            finally { exchange.close(); }
        });
        Path adapter = Files.createDirectory(temporary.resolve("adapter"));
        String providerId = "mail-probe-offline-" + UUID.randomUUID();
        JsonObject config = new JsonObject();
        config.addProperty("ProviderId", providerId);
        config.addProperty("APIKey", "offline-only");
        config.addProperty("BaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        config.addProperty("Search", false);
        Files.writeString(adapter.resolve("provider.json"), config.toString());
        Path global = temporary.resolve("aichat.json");
        Files.writeString(global, "{\"Librarian\":{\"provider\":\"" + providerId + "\",\"model\":\"qwen3.7-plus\"}}");
        Path output = temporary.resolve("artifacts");
        String previousOutput = System.getProperty("mail.probe.output");
        Map<String, Object> previousProviders = new HashMap<>(Universe.Providers);
        server.start();
        try {
            System.setProperty("mail.probe.output", output.toString());
            MailResearchPromptProbe.main(new String[]{adapter.toString(), global.toString()});
            JsonObject sent = wire.get();
            assertNotNull(sent);
            assertEquals("qwen3.7-plus", sent.get("model").getAsString());
            assertTrue(sent.get("stream").getAsBoolean(), "Use the project's SSE transport");
            assertEquals("json_schema", sent.getAsJsonObject("response_format").get("type").getAsString());
            assertTrue(sent.getAsJsonObject("response_format").getAsJsonObject("json_schema").get("strict").getAsBoolean());
            assertFalse(sent.has("tools"));
            assertFalse(sent.has("enable_search"));
            JsonArray messages = sent.getAsJsonArray("messages");
            assertEquals(3, messages.size(), "Adapter prepends its schema instruction to the two business messages");
            assertTrue(messages.get(0).toString().contains("字段定义"));
            assertTrue(messages.get(1).toString().contains("你负责规划"));
            try (var files = Files.walk(output)) {
                Path result = files.filter(p -> p.getFileName().toString().equals("result.json")).findFirst().orElseThrow();
                assertEquals(JsonParser.parseString(OUTPUT), read(result));
                assertEquals(2, read(result.resolveSibling("request.json")).getAsJsonArray("messages").size());
                assertFalse(Files.readString(result.resolveSibling("request.json")).contains("offline-only"));
            }
            assertEquals(previousProviders, Universe.Providers, "Probe restores provider registrations");
        } finally {
            server.stop(0);
            if (previousOutput == null) System.clearProperty("mail.probe.output");
            else System.setProperty("mail.probe.output", previousOutput);
        }
    }

    private static void verifySchema(JsonSchema schema) {
        if (schema.properties != null) {
            assertEquals(Boolean.FALSE, schema.additionalProperties);
            assertEquals(schema.properties.keySet(), new HashSet<>(schema.required));
            schema.properties.values().forEach(MailResearchPromptProbeTest::verifySchema);
        }
        if (schema.items != null) verifySchema(schema.items);
        if (schema.anyOf != null) schema.anyOf.forEach(MailResearchPromptProbeTest::verifySchema);
    }

    private static JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static CompletionsResponse response(String text, String finish) {
        CompletionsResponse response = new CompletionsResponse();
        response.choices = new CompletionsResponse.Choice[]{new CompletionsResponse.Choice()};
        response.choices[0].message.content = text;
        response.choices[0].finish_reason = finish;
        return response;
    }

    @FunctionalInterface private interface Call {
        CompletionsResponse invoke(String model, List<Message> messages, List<LLMFunction> tools, CompletionsRequest request) throws IOException;
    }

    private static LLMProvider provider(Call call) {
        return new LLMProvider() {
            @Override public CompletionsResponse completions(String model, List<Message> messages, List<LLMFunction> tools,
                                                             CompletionsRequest request, BufferCallback callback) throws IOException {
                return call.invoke(model, messages, tools, request);
            }
        };
    }
}
