package com.nekoyu.universe.dashscope_adapter;

import com.google.gson.*;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.Providers.LLMProvider.Embedding;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.EmbeddingRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 百炼协议转换集中在 Adapter；输入、结果和空间身份对外使用 Universe 中性接口。 */
final class DashScopeEmbedding {
    static final String ROUTE = "/services/embeddings/multimodal-embedding/multimodal-embedding";
    static final int RESPONSE_LIMIT = 2 * 1024 * 1024;
    private final DashScopeChannel channel;
    DashScopeEmbedding(DashScopeChannel channel) { this.channel = channel; }
    private record Input(String text, String image) {
        String type() { return text != null && image != null ? "fused" : image != null ? "image" : "text"; }
        JsonObject json() {
            var item = new JsonObject();
            if (text != null) item.addProperty("text", text);
            if (image != null) item.addProperty("image", image);
            return item;
        }
    }
    private boolean qwen(String model) { return model.equals("qwen3-vl-embedding"); }
    Embedding.Space space(EmbeddingRequest request) throws IOException {
        if (request == null) throw new IOException("Embedding request is required");
        String model = request.model == null || request.model.isBlank() ? channel.defaultEmbeddingModel : request.model;
        Set<Integer> dimensions;
        int defaultDimension;
        if ("qwen3-vl-embedding".equals(model)) {
            dimensions = Set.of(2560, 2048, 1536, 1024, 768, 512, 256); defaultDimension = 2560;
        } else if ("tongyi-embedding-vision-plus-2026-03-06".equals(model)) {
            dimensions = Set.of(1152, 1024, 512, 256, 128, 64); defaultDimension = 1152;
        } else if ("tongyi-embedding-vision-flash-2026-03-06".equals(model)) {
            dimensions = Set.of(768, 512, 256, 128, 64); defaultDimension = 768;
        } else throw new IOException("Unsupported multimodal embedding model: " + model);
        int dimension = request.dimension == null ? defaultDimension : request.dimension;
        if (!dimensions.contains(dimension)) throw new IOException("Unsupported embedding dimension");
        int resolution = resolution(request);
        if (qwen(model) && resolution != 1) throw new IOException("Qwen does not support resolutionLevel");
        String version = request.spaceVersion == null ? channel.embeddingSpaceVersion : request.spaceVersion;
        if (version == null || version.isBlank()) throw new IOException("Embedding space version is required");
        if (!version.equals(channel.embeddingSpaceVersion)) throw new IOException("Requested space version differs from Provider configuration");
        String revision = (request.enableFusion ? "dashscope-fused-v1@" : "dashscope-independent-v1@")
                + endpoint() + "#space=" + version + "#res=" + resolution;
        return new Embedding.Space(model, revision, dimension);
    }
    private int resolution(EmbeddingRequest request) throws IOException {
        int value = request.resolutionLevel == null ? channel.embeddingResolutionLevel : request.resolutionLevel;
        if (value < 0 || value > 3) throw new IOException("resolutionLevel must be 0-3");
        return value;
    }
    private URI endpoint() throws IOException {
        try {
            String base = channel.baseurl == null ? "https://dashscope.aliyuncs.com/api/v1" : channel.baseurl;
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            URI uri = URI.create(base.endsWith(ROUTE) ? base : base + ROUTE).normalize();
            if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getPath().endsWith("/api/v1" + ROUTE)) throw new IOException("BaseUrl must be a native DashScope /api/v1 URL");
            return uri;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid DashScope BaseUrl", e); }
    }
    EmbeddingResponse embedding(EmbeddingRequest request) throws IOException {
        var space = space(request);
        List<Input> inputs = inputs(request);
        var result = new EmbeddingResponse(); result.model = space.model(); result.object = "list";
        if (inputs.isEmpty()) return result;
        String key = channel.apikey;
        if (key == null || key.isBlank()) throw new IOException("Embedding API key is missing");
        // Qwen 融合整次请求，因此逐输入项请求，保持中性接口的一项一向量；独立图片沿用单张请求。
        int batchSize = qwen(space.model()) && (request.enableFusion || inputs.stream().anyMatch(i -> i.image != null)) ? 1 : 20;
        for (int start = 0; start < inputs.size(); start += batchSize) {
            var batch = inputs.subList(start, Math.min(start + batchSize, inputs.size()));
            var contents = new JsonArray();
            for (Input item : batch) {
                if (qwen(space.model()) && request.enableFusion) {
                    if (item.text != null) { var text = new JsonObject(); text.addProperty("text", item.text); contents.add(text); }
                    if (item.image != null) { var image = new JsonObject(); image.addProperty("image", item.image); contents.add(image); }
                } else contents.add(item.json());
            }
            var body = new JsonObject(); body.addProperty("model", space.model());
            var input = new JsonObject(); input.add("contents", contents); body.add("input", input);
            var parameters = new JsonObject(); parameters.addProperty("dimension", space.dimension());
            if (qwen(space.model())) parameters.addProperty("enable_fusion", request.enableFusion);
            else parameters.addProperty("res_level", resolution(request));
            body.add("parameters", parameters);
            var http = new Request.Builder().url(endpoint().toString()).header("Authorization", "Bearer " + key)
                    .post(RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8"))).build();
            try (var response = channel.client.newCall(http).execute()) {
                if (!response.isSuccessful()) throw new IOException("Embedding HTTP " + response.code());
                if (response.body() == null || response.body().contentLength() > RESPONSE_LIMIT) throw new IOException("Invalid embedding response size");
                byte[] bytes = response.body().byteStream().readNBytes(RESPONSE_LIMIT + 1);
                if (bytes.length > RESPONSE_LIMIT) throw new IOException("Embedding response exceeds size limit");
                append(result, JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(), batch, start, space, request.enableFusion);
            } catch (RuntimeException e) { throw new IOException("Malformed embedding response", e); }
        }
        if (channel.logger != null) channel.logger.info("Embedding-Usage: ({}) {} Tokens", space.model(), result.usage.prompt_tokens);
        return result;
    }
    private List<Input> inputs(EmbeddingRequest request) throws IOException {
        var groups = new ArrayList<List<ContentPiece>>();
        if (request.inputs != null && !request.inputs.isEmpty()) {
            if (request.message != null && !request.message.isEmpty()) throw new IOException("Use inputs or message, not both");
            groups.addAll(request.inputs);
        } else if (request.message != null) {
            for (var message : request.message) {
                if (message == null) throw new IOException("Null embedding input");
                var pieces = new ArrayList<ContentPiece>();
                for (var field : message) {
                    if (field instanceof TextField text) pieces.add(new TextPiece(text.toString()));
                    else if (field instanceof ImageField image) pieces.add(new ImageUrlPiece(image.getUrl().toString()));
                    else throw new IOException("Unsupported embedding message field");
                }
                groups.add(pieces);
            }
        }
        var result = new ArrayList<Input>();
        for (var group : groups) {
            if (group == null) throw new IOException("Null embedding input");
            var text = new StringBuilder(); boolean hasText = false; String image = null;
            for (var piece : group) {
                if (piece instanceof TextPiece value && value.getText() != null) { text.append(value.getText()); hasText = true; }
                else if (piece instanceof ImageUrlPiece value && image == null) image = image(value.getUrl());
                else if (piece instanceof ImageBytesPiece value && image == null) {
                    byte[] bytes = value.getBytes();
                    if (bytes.length > 5 * 1024 * 1024) throw new IOException("Image input exceeds 5 MiB");
                    image = image("data:" + value.getMimeType() + ";base64," + Base64.getEncoder().encodeToString(bytes));
                }
                else throw new IOException("Only text and one image per embedding input are supported");
            }
            if ((!hasText && image == null) || (hasText && text.toString().isBlank())) throw new IOException("Empty embedding input");
            if (hasText && image != null && !request.enableFusion) throw new IOException("Mixed input requires enableFusion");
            result.add(new Input(hasText ? text.toString() : null, image));
        }
        return result;
    }
    private String image(String value) throws IOException {
        if (value == null) throw new IOException("Image URL is required");
        try {
            if (value.startsWith("data:")) {
                int comma = value.indexOf(',');
                if (comma < 0 || !Set.of("data:image/png;base64", "data:image/jpeg;base64", "data:image/webp;base64", "data:image/bmp;base64", "data:image/tiff;base64", "data:image/x-icon;base64").contains(value.substring(0, comma)))
                    throw new IOException("Unsupported image data URI");
                if (value.length() > 7 * 1024 * 1024 || Base64.getDecoder().decode(value.substring(comma + 1)).length == 0) throw new IOException("Invalid image data size");
            } else {
                var uri = URI.create(value);
                if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IOException("Expected HTTP image URL or data URI");
            }
            return value;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid image input", e); }
    }
    private void append(EmbeddingResponse result, JsonObject raw, List<Input> batch, int offset, Embedding.Space space, boolean fusion) throws IOException {
        if (raw.has("code")) throw new IOException("Embedding API returned an error");
        var entries = raw.getAsJsonObject("output").getAsJsonArray("embeddings");
        if (entries.size() != batch.size()) throw new IOException("Embedding result count mismatch");
        var ordered = new EmbeddingResponse.Embedding[batch.size()];
        for (var value : entries) {
            var entry = value.getAsJsonObject();
            if (!entry.get("index").getAsJsonPrimitive().isNumber()) throw new IOException("Invalid embedding index");
            int index = entry.get("index").getAsBigDecimal().intValueExact();
            if (index < 0 || index >= ordered.length || ordered[index] != null) throw new IOException("Invalid embedding index");
            String type = entry.get("type").getAsString();
            String expected = qwen(space.model()) ? fusion ? "fusion" : "vl" : batch.get(index).type();
            if (!type.equals(expected) && !(qwen(space.model()) && !fusion && type.equals(batch.get(index).type()))) throw new IOException("Unexpected embedding type");
            var values = entry.getAsJsonArray("embedding");
            if (values.size() != space.dimension()) throw new IOException("Embedding dimension mismatch");
            var row = new EmbeddingResponse.Embedding(); row.index = offset + index; row.object = "embedding";
            row.embedding = new double[values.size()]; double norm = 0;
            for (int i = 0; i < values.size(); i++) {
                if (!values.get(i).getAsJsonPrimitive().isNumber()) throw new IOException("Non-numeric embedding value");
                double number = values.get(i).getAsDouble();
                if (!Double.isFinite(number)) throw new IOException("Non-finite embedding value");
                row.embedding[i] = number; norm = Math.hypot(norm, number);
            }
            if (!Double.isFinite(norm) || norm == 0) throw new IOException("Zero or invalid embedding vector");
            ordered[index] = row;
        }
        result.data.addAll(Arrays.asList(ordered));
        if (raw.has("request_id")) result.id = raw.get("request_id").getAsString();
        if (raw.has("usage") && raw.getAsJsonObject("usage").has("input_tokens")) {
            int tokens = raw.getAsJsonObject("usage").get("input_tokens").getAsBigDecimal().intValueExact();
            if (tokens < 0) throw new IOException("Invalid embedding usage");
            result.usage.prompt_tokens = Math.addExact(result.usage.prompt_tokens, tokens);
            result.usage.total_tokens = result.usage.prompt_tokens;
        }
    }
}
