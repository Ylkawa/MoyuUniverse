package com.nekoyu.Universe.AIChat.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** DashScope native multimodal endpoint; independent vectors, never batch fusion. */
public final class Qwen3VlEmbeddingProvider implements IconEmbeddingProvider {
    public static final String MODEL = "qwen3-vl-embedding";
    public static final URI DEFAULT_ENDPOINT = URI.create("https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding");
    private static final Set<Integer> DIMENSIONS = Set.of(2560, 2048, 1536, 1024, 768, 512, 256);
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final Space space;
    private final Duration timeout;

    public Qwen3VlEmbeddingProvider(String apiKey) { this(apiKey, 2560); }
    public Qwen3VlEmbeddingProvider(String apiKey, int dimension) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), DEFAULT_ENDPOINT, apiKey, dimension, Duration.ofSeconds(60));
    }
    public Qwen3VlEmbeddingProvider(HttpClient client, URI endpoint, String apiKey, int dimension, Duration timeout) {
        this.client = Objects.requireNonNull(client); this.endpoint = Objects.requireNonNull(endpoint);
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null)
            throw new IllegalArgumentException("Expected an HTTP/HTTPS embedding endpoint");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("API key is required");
        if (!DIMENSIONS.contains(dimension)) throw new IllegalArgumentException("Unsupported Qwen3-VL dimension: " + dimension);
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive timeout is required");
        this.apiKey = apiKey; this.timeout = timeout;
        // Include endpoint identity: a self-hosted compatible model must not reuse cloud vectors.
        this.space = new Space(MODEL, "dashscope-independent-v1@" + endpoint.normalize(), dimension);
    }
    @Override public Space space() { return space; }

    @Override public List<double[]> embedTexts(List<String> texts) throws IOException {
        List<JsonObject> contents = new ArrayList<>();
        for (String text : List.copyOf(texts)) {
            if (text.isBlank()) throw new IOException("Icon query is blank");
            JsonObject entry = new JsonObject(); entry.addProperty("text", text); contents.add(entry);
        }
        return batches(contents, 20);
    }
    @Override public List<double[]> embedImages(List<Image> images) throws IOException {
        List<JsonObject> contents = new ArrayList<>();
        for (Image image : List.copyOf(images)) {
            byte[] bytes = image.bytes();
            if (!Set.of("image/png", "image/jpeg", "image/webp", "image/bmp", "image/tiff", "image/x-icon").contains(image.mimeType())
                    || bytes.length > 5 * 1024 * 1024) throw new IOException("Unsupported or oversized icon image: " + image.id());
            JsonObject entry = new JsonObject();
            entry.addProperty("image", "data:" + image.mimeType() + ";base64," + Base64.getEncoder().encodeToString(bytes));
            contents.add(entry);
        }
        // One image per call also satisfies endpoints that enforce the documented single-image limit.
        return batches(contents, 1);
    }
    private List<double[]> batches(List<JsonObject> entries, int batchSize) throws IOException {
        List<double[]> output = new ArrayList<>();
        for (int start = 0; start < entries.size(); start += batchSize) {
            List<JsonObject> batch = entries.subList(start, Math.min(start + batchSize, entries.size()));
            JsonObject body = new JsonObject(); body.addProperty("model", MODEL);
            JsonArray contents = new JsonArray(); batch.forEach(contents::add);
            JsonObject input = new JsonObject(); input.add("contents", contents); body.add("input", input);
            JsonObject parameters = new JsonObject(); parameters.addProperty("dimension", space.dimension());
            parameters.addProperty("enable_fusion", false); body.add("parameters", parameters);
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpResponse<String> response;
            try { response = client.send(request, HttpResponse.BodyHandlers.ofString()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Qwen embedding request interrupted", e); }
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IOException("Qwen embedding HTTP " + response.statusCode());
            try {
                JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
                if (result.has("code")) throw new IOException("Qwen embedding API error: " + result.get("code").getAsString());
                JsonArray embeddings = result.getAsJsonObject("output").getAsJsonArray("embeddings");
                if (embeddings.size() != batch.size()) throw new IOException("Qwen embedding count mismatch");
                double[][] ordered = new double[batch.size()][];
                for (JsonElement raw : embeddings) {
                    JsonObject entry = raw.getAsJsonObject();
                    if (!entry.get("index").getAsJsonPrimitive().isNumber()) throw new IOException("Invalid Qwen embedding index");
                    int index = entry.get("index").getAsBigDecimal().intValueExact();
                    if (index < 0 || index >= ordered.length || ordered[index] != null) throw new IOException("Invalid Qwen embedding index");
                    String type = entry.get("type").getAsString();
                    String expectedType = batch.get(index).has("text") ? "text" : "image";
                    if (!type.equals("vl") && !type.equals(expectedType)) throw new IOException("Unexpected Qwen embedding type: " + type);
                    JsonArray values = entry.getAsJsonArray("embedding");
                    double[] vector = new double[values.size()];
                    for (int i = 0; i < values.size(); i++) {
                        if (!values.get(i).getAsJsonPrimitive().isNumber()) throw new IOException("Non-numeric Qwen embedding value");
                        vector[i] = values.get(i).getAsDouble();
                    }
                    ordered[index] = vector;
                }
                output.addAll(Vectors.batch(Arrays.asList(ordered), batch.size(), space.dimension()));
            } catch (RuntimeException e) { throw new IOException("Malformed Qwen embedding response", e); }
        }
        return List.copyOf(output);
    }
}
