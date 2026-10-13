package com.nekoyu.universe.dashscope_adapter;

import com.google.gson.*;
import com.nekoyu.Universe.Universe;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.Providers.LLMProvider.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.*;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class DashScopeChannelTest {
    HttpServer server;
    DashScopeChannel channel;
    List<JsonObject> requests = new CopyOnWriteArrayList<>();
    List<String> authorization = new CopyOnWriteArrayList<>();
    volatile Function<JsonObject, JsonObject> respond = this::response;
    volatile int status = 200;
    @TempDir Path directory;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1" + DashScopeEmbedding.ROUTE, exchange -> {
            var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            requests.add(request); authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = respond.apply(request).toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start(); channel = new DashScopeChannel(); channel.baseurl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
        channel.apikey = "fixture-secret"; channel.defaultEmbeddingModel = "tongyi-embedding-vision-plus-2026-03-06";
    }
    @AfterEach void stop() { server.stop(0); }
    EmbeddingRequest options() { var request = new EmbeddingRequest(); request.dimension = 256; return request; }
    EmbeddingRequest texts(int count) {
        var request = options(); IntStream.rangeClosed(1, count).forEach(i -> request.inputs.add(List.of(new TextPiece(Integer.toString(i))))); return request;
    }
    private JsonObject response(JsonObject request) {
        var contents = request.getAsJsonObject("input").getAsJsonArray("contents");
        boolean qwen = request.get("model").getAsString().equals("qwen3-vl-embedding");
        boolean fusion = qwen && request.getAsJsonObject("parameters").get("enable_fusion").getAsBoolean();
        int count = fusion ? 1 : contents.size();
        var embeddings = new JsonArray();
        for (int i = count - 1; i >= 0; i--) {
            var content = contents.get(i).getAsJsonObject();
            var entry = new JsonObject(); entry.addProperty("index", i);
            entry.addProperty("type", qwen ? fusion ? "fusion" : "vl" : content.size() == 2 ? "fused" : content.has("text") ? "text" : "image");
            double first = content.has("text") && content.get("text").getAsString().matches("[0-9]+") ? Double.parseDouble(content.get("text").getAsString()) : 1;
            var values = new JsonArray(); int dimension = request.getAsJsonObject("parameters").get("dimension").getAsInt();
            for (int j = 0; j < dimension; j++) values.add(j == 0 ? first : 0);
            entry.add("embedding", values); embeddings.add(entry);
        }
        var output = new JsonObject(); output.add("embeddings", embeddings);
        var usage = new JsonObject(); usage.addProperty("input_tokens", count * 3);
        var result = new JsonObject(); result.add("output", output); result.add("usage", usage); result.addProperty("request_id", "fixture-id"); return result;
    }
    @Test void batchesAndReordersNeutralResponseWithGlobalIndices() throws Exception {
        Embedding capability = channel;
        var result = capability.embedding(texts(21));
        assertEquals(21, result.data.size()); assertEquals(2, requests.size()); assertEquals(63, result.usage.prompt_tokens);
        assertEquals(63, result.usage.total_tokens); assertEquals("fixture-id", result.id);
        for (int i = 0; i < 21; i++) { assertEquals(i, result.data.get(i).index); assertEquals(i + 1, result.data.get(i).embedding[0]); }
        assertEquals("Bearer fixture-secret", authorization.get(0));
    }
    @Test void independentImagesAreEncodedByAdapter() throws Exception {
        var request = options(); byte[] bytes = {1, 2, 3}; var piece = new ImageBytesPiece("image/png", bytes); bytes[0] = 9;
        request.inputs.add(List.of(piece)); request.inputs.add(List.of(new ImageUrlPiece("https://example.test/icon.png")));
        assertEquals(2, channel.embedding(request).data.size()); assertEquals(1, requests.size());
        var body = requests.get(0); var contents = body.getAsJsonObject("input").getAsJsonArray("contents");
        assertEquals("data:image/png;base64,AQID", contents.get(0).getAsJsonObject().get("image").getAsString());
        assertFalse(contents.get(0).getAsJsonObject().has("text"));
        assertEquals(1, body.getAsJsonObject("parameters").get("res_level").getAsInt());
        assertFalse(body.getAsJsonObject("parameters").has("enable_fusion"));
    }
    @Test void legacyMfChainUsesRealImagesAndPreservesTextExactly() throws Exception {
        var request = options(); request.message.add(new MFChain("  邮件 A  "));
        request.message.add(new MFChain(new ImageField(URI.create("https://example.test/image.png").toURL())));
        channel.embedding(request);
        var contents = requests.get(0).getAsJsonObject("input").getAsJsonArray("contents");
        assertEquals("  邮件 A  ", contents.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("https://example.test/image.png", contents.get(1).getAsJsonObject().get("image").getAsString());
    }
    @Test void snapshotFusionGroupsOneInputIntoOneVector() throws Exception {
        var request = options(); request.enableFusion = true;
        request.inputs.add(List.of(new TextPiece("邮件信封"), new ImageBytesPiece("image/png", new byte[]{1})));
        request.inputs.add(List.of(new TextPiece("云端"), new ImageBytesPiece("image/png", new byte[]{2})));
        assertEquals(2, channel.embedding(request).data.size()); assertEquals(1, requests.size());
        assertEquals(2, requests.get(0).getAsJsonObject("input").getAsJsonArray("contents").get(0).getAsJsonObject().size());
        assertFalse(requests.get(0).getAsJsonObject("parameters").has("enable_fusion"));
    }
    @Test void qwenFusionUsesOneCallPerGroupAndIndependentImagesUseSeparateCalls() throws Exception {
        channel.defaultEmbeddingModel = "qwen3-vl-embedding";
        var request = options(); request.enableFusion = true;
        request.inputs.add(List.of(new TextPiece("物件"), new ImageBytesPiece("image/png", new byte[]{1})));
        request.inputs.add(List.of(new TextPiece("设备"), new ImageBytesPiece("image/png", new byte[]{2})));
        assertEquals(2, channel.embedding(request).data.size()); assertEquals(2, requests.size());
        assertTrue(requests.get(0).getAsJsonObject("parameters").get("enable_fusion").getAsBoolean());
        assertEquals(2, requests.get(0).getAsJsonObject("input").getAsJsonArray("contents").size());
        requests.clear(); request.enableFusion = false; request.inputs = new ArrayList<>(List.of(List.of(new ImageBytesPiece("image/png", new byte[]{1})), List.of(new ImageBytesPiece("image/png", new byte[]{2}))));
        channel.embedding(request); assertEquals(2, requests.size());
    }
    @Test void emptyRequestAndSpaceMetadataNeedNoKeyAndDoNotCallNetwork() throws Exception {
        channel.apikey = null;
        assertTrue(channel.embedding(options()).data.isEmpty());
        var space = channel.embeddingSpace(options()); assertEquals(256, space.dimension());
        assertTrue(space.revision().contains("dashscope-independent-v1@")); assertTrue(requests.isEmpty());
        assertThrows(IOException.class, () -> channel.embedding(texts(1)));
        var other = options(); other.spaceVersion = "v2"; assertThrows(IOException.class, () -> channel.embeddingSpace(other));
        channel.embeddingSpaceVersion = "v2"; assertNotEquals(space, channel.embeddingSpace(other)); channel.embeddingSpaceVersion = "v1";
        other.spaceVersion = "v1"; other.enableFusion = true; assertNotEquals(space, channel.embeddingSpace(other));
        other.enableFusion = false; other.resolutionLevel = 0; assertNotEquals(space, channel.embeddingSpace(other));
    }
    @Test void rejectsInvalidInputsBeforeNetwork() throws Exception {
        var request = options(); request.inputs.add(List.of(new TextPiece("a"), new ImageBytesPiece("image/png", new byte[]{1})));
        assertThrows(IOException.class, () -> channel.embedding(request));
        request.inputs = List.of(List.of(new ImageUrlPiece("file:///private/image.png")));
        assertThrows(IOException.class, () -> channel.embedding(request));
        request.inputs = List.of(List.of(new TextPiece(" ")));
        assertThrows(IOException.class, () -> channel.embedding(request));
        request.inputs = List.of(List.of(new TextPiece("a"))); request.message.add(new MFChain("b"));
        assertThrows(IOException.class, () -> channel.embedding(request));
        request.dimension = 3; assertThrows(IOException.class, () -> channel.embeddingSpace(request)); assertTrue(requests.isEmpty());
    }
    @Test void rejectsWrongIndicesTypesCountsDimensionsAndValues() {
        var failures = new ArrayList<Function<JsonObject, JsonObject>>();
        failures.add(r -> { var out = response(r); out.getAsJsonObject("output").getAsJsonArray("embeddings").remove(0); return out; });
        for (double index : new double[]{99, 0.5, 0}) failures.add(r -> { var out = response(r); out.getAsJsonObject("output").getAsJsonArray("embeddings").get(0).getAsJsonObject().addProperty("index", index); return out; });
        failures.add(r -> { var out = response(r); out.getAsJsonObject("output").getAsJsonArray("embeddings").get(0).getAsJsonObject().addProperty("type", "fused"); return out; });
        failures.add(r -> { var out = response(r); out.getAsJsonObject("output").getAsJsonArray("embeddings").get(0).getAsJsonObject().getAsJsonArray("embedding").remove(0); return out; });
        failures.add(r -> { var out = response(r); out.getAsJsonObject("output").getAsJsonArray("embeddings").get(0).getAsJsonObject().getAsJsonArray("embedding").set(0, new JsonPrimitive(0)); return out; });
        failures.add(r -> { var out = response(r); out.getAsJsonObject("output").getAsJsonArray("embeddings").get(0).getAsJsonObject().getAsJsonArray("embedding").set(0, new JsonPrimitive("NaN")); return out; });
        for (var failure : failures) { respond = failure; assertThrows(IOException.class, () -> channel.embedding(texts(2))); }
    }
    @Test void errorsNeverExposeCredentialsOrRemoteBody() {
        respond = r -> { var error = new JsonObject(); error.addProperty("code", "InvalidApiKey"); error.addProperty("message", "fixture-secret"); return error; };
        status = 401; var http = assertThrows(IOException.class, () -> channel.embedding(texts(1))); assertFalse(http.toString().contains("fixture-secret"));
        status = 200; var api = assertThrows(IOException.class, () -> channel.embedding(texts(1))); assertFalse(api.toString().contains("fixture-secret"));
    }
    @Test void boundsResponseAndWholeCallTimeout() throws Exception {
        respond = r -> { var json = new JsonObject(); json.addProperty("large", "x".repeat(DashScopeEmbedding.RESPONSE_LIMIT)); return json; };
        assertThrows(IOException.class, () -> channel.embedding(texts(1)));
        respond = r -> { try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return response(r); };
        channel.client = new OkHttpClient.Builder().callTimeout(30, TimeUnit.MILLISECONDS).build();
        long start = System.nanoTime(); assertThrows(IOException.class, () -> channel.embedding(texts(1)));
        assertTrue((System.nanoTime() - start) / 1e6 < 1000);
    }
    @Test void configurationRegistersLlmProviderAndStopRestoresPreviousEntry() throws Exception {
        String id = "dashscope-registration-test"; Object previous = new Object(); Universe.Providers.put(id, previous);
        Files.writeString(directory.resolve("channel.json"), new Gson().toJson(Map.of("ProviderId", id, "BaseUrl", channel.baseurl,
                "DefaultEmbeddingModel", channel.defaultEmbeddingModel, "APIKey", "fixture-secret")));
        var adapter = new DashScopeAdapter();
        try {
            assertTrue(adapter.prepareDirectory(directory));
            assertInstanceOf(LLMProvider.class, Universe.Providers.get(id));
            var provider = (Embedding) Universe.Providers.get(id); assertEquals(1152, provider.embeddingSpace(new EmbeddingRequest()).dimension());
            assertTrue(requests.isEmpty());
            assertEquals(1, provider.embedding(texts(1)).data.size());
            assertEquals("Bearer fixture-secret", authorization.get(0));
        } finally { adapter.stop(); }
        assertSame(previous, Universe.Providers.remove(id));
    }
}
