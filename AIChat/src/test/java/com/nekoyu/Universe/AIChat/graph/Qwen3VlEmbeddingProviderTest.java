package com.nekoyu.Universe.AIChat.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class Qwen3VlEmbeddingProviderTest {
    HttpServer server;
    Qwen3VlEmbeddingProvider provider;
    List<JsonObject> requests = new CopyOnWriteArrayList<>();
    List<String> authorization = new CopyOnWriteArrayList<>();
    volatile Function<JsonObject, JsonObject> respond = this::response;
    volatile int status = 200;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embedding", exchange -> {
            JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            requests.add(request); authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = respond.apply(request).toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        provider = new Qwen3VlEmbeddingProvider(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/embedding"),
                "fixture-secret", 256, Duration.ofSeconds(5));
    }
    @AfterEach void stop() { server.stop(0); }
    private JsonObject response(JsonObject request) {
        JsonArray contents = request.getAsJsonObject("input").getAsJsonArray("contents");
        JsonArray embeddings = new JsonArray();
        for (int i = contents.size() - 1; i >= 0; i--) {
            JsonObject entry = new JsonObject(); entry.addProperty("index", i); entry.addProperty("type", "vl");
            JsonArray values = new JsonArray();
            boolean text = contents.get(i).getAsJsonObject().has("text");
            double first = text ? Double.parseDouble(contents.get(i).getAsJsonObject().get("text").getAsString()) : 1;
            for (int j = 0; j < 256; j++) values.add(j == 0 ? first : 0);
            entry.add("embedding", values); embeddings.add(entry);
        }
        JsonObject output = new JsonObject(); output.add("embeddings", embeddings);
        JsonObject result = new JsonObject(); result.add("output", output); return result;
    }
    @Test void batchesTextsAndRestoresIndexOrder() throws Exception {
        List<String> texts = IntStream.rangeClosed(1, 21).mapToObj(Integer::toString).toList();
        List<double[]> result = provider.embedTexts(texts);
        assertEquals(21, result.size()); assertEquals(2, requests.size());
        assertEquals(20, requests.get(0).getAsJsonObject("input").getAsJsonArray("contents").size());
        for (int i = 0; i < 21; i++) assertEquals(i + 1, result.get(i)[0]);
        for (JsonObject request : requests) {
            assertEquals("qwen3-vl-embedding", request.get("model").getAsString());
            assertEquals(256, request.getAsJsonObject("parameters").get("dimension").getAsInt());
            assertFalse(request.getAsJsonObject("parameters").get("enable_fusion").getAsBoolean());
        }
        assertEquals("Bearer fixture-secret", authorization.get(0));
    }
    @Test void encodesImagesAsDataUriWithOneIndependentVectorPerIcon() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3};
        var image = new IconEmbeddingProvider.Image("one", "image/png", bytes);
        bytes[0] = 9;
        assertEquals(1, image.bytes()[0]);
        assertEquals(2, provider.embedImages(List.of(image, image)).size());
        assertEquals(2, requests.size());
        for (JsonObject request : requests) {
            JsonArray contents = request.getAsJsonObject("input").getAsJsonArray("contents");
            assertEquals(1, contents.size());
            assertEquals("data:image/png;base64,AQID", contents.get(0).getAsJsonObject().get("image").getAsString());
            assertFalse(contents.get(0).getAsJsonObject().has("text"));
        }
    }
    @Test void emptyInputMakesNoNetworkCallAndModelDimensionIsFixed() throws Exception {
        assertTrue(provider.embedTexts(List.of()).isEmpty()); assertTrue(provider.embedImages(List.of()).isEmpty());
        assertTrue(requests.isEmpty()); assertEquals("qwen3-vl-embedding", provider.space().model());
        assertThrows(IllegalArgumentException.class, () -> new Qwen3VlEmbeddingProvider("key", 2));
        assertThrows(IOException.class, () -> provider.embedTexts(List.of(" ")));
    }
    @Test void rejectsIncompleteDuplicateAndWrongVectors() {
        List<Function<JsonObject, JsonObject>> failures = new ArrayList<>();
        failures.add(request -> { JsonObject out = response(request); out.getAsJsonObject("output").getAsJsonArray("embeddings").remove(0); return out; });
        failures.add(request -> { JsonObject out = response(request); out.getAsJsonObject("output").getAsJsonArray("embeddings").get(0).getAsJsonObject().addProperty("index", 99); return out; });
        failures.add(request -> { JsonObject out = response(request); out.getAsJsonObject("output").getAsJsonArray("embeddings").get(0).getAsJsonObject().addProperty("index", 0.5); return out; });
        failures.add(request -> { JsonObject out = response(request); out.getAsJsonObject("output").getAsJsonArray("embeddings").get(0).getAsJsonObject().addProperty("index", 0); return out; });
        failures.add(request -> { JsonObject out = response(request); out.getAsJsonObject("output").getAsJsonArray("embeddings").get(0).getAsJsonObject().addProperty("type", "fusion"); return out; });
        failures.add(request -> { JsonObject out = response(request); out.getAsJsonObject("output").getAsJsonArray("embeddings").get(0).getAsJsonObject().getAsJsonArray("embedding").remove(0); return out; });
        for (Function<JsonObject, JsonObject> failure : failures) {
            respond = failure;
            assertThrows(IOException.class, () -> provider.embedTexts(List.of("1", "2")));
        }
    }
    @Test void apiAndHttpErrorsDoNotLeakCredentialsOrPayloads() {
        status = 401;
        respond = request -> { JsonObject error = new JsonObject(); error.addProperty("code", "InvalidApiKey"); error.addProperty("message", "fixture-secret"); return error; };
        var http = assertThrows(IOException.class, () -> provider.embedTexts(List.of("1")));
        assertFalse(http.getMessage().contains("fixture-secret"));
        status = 200;
        var api = assertThrows(IOException.class, () -> provider.embedTexts(List.of("1")));
        assertTrue(api.getMessage().contains("InvalidApiKey")); assertFalse(api.getMessage().contains("fixture-secret"));
    }
}
