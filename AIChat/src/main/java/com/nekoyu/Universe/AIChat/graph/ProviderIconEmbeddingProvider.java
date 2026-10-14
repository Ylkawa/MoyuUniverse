package com.nekoyu.Universe.AIChat.graph;

import com.nekoyu.Universe.Universe;
import com.nekoyu.Universe.API.Providers.LLMProvider.Embedding;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.EmbeddingRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 图标业务只适配中性请求与已注册的 LLMProvider，不持有厂商 HTTP 或密钥配置。 */
public final class ProviderIconEmbeddingProvider implements IconEmbeddingProvider {
    private final String providerId, model, version;
    private final int dimension;
    private final Space space;
    private final AtomicLong inputTokens = new AtomicLong();
    public ProviderIconEmbeddingProvider(String providerId, String model, int dimension, String version) throws IOException {
        this.providerId = providerId; this.model = model; this.dimension = dimension; this.version = version;
        var identity = provider().embeddingSpace(options());
        if (identity.dimension() != dimension || (model != null && !model.equals(identity.model())))
            throw new IOException("Embedding Provider does not honor requested model/dimension");
        space = EmbeddingSpaces.canonical(new Space(identity.model(), identity.revision(), identity.dimension()));
    }
    private Embedding provider() throws IOException {
        var candidate = providerId == null ? null : Universe.Providers.get(providerId);
        if (!(candidate instanceof LLMProvider) || !(candidate instanceof Embedding embedding))
            throw new IOException("Multimodal Embedding LLMProvider not registered: " + providerId);
        return embedding;
    }
    private EmbeddingRequest options() {
        var request = new EmbeddingRequest(); request.model = model; request.dimension = dimension;
        request.spaceVersion = version; request.resolutionLevel = 1; request.enableFusion = false;
        return request;
    }
    @Override public Space space() { return space; }
    public long inputTokens() { return inputTokens.get(); }
    @Override public List<double[]> embedTexts(List<String> texts) throws IOException {
        var request = options();
        for (String text : texts) request.inputs.add(List.of(new TextPiece(text)));
        return call(request);
    }
    @Override public List<double[]> embedImages(List<Image> images) throws IOException {
        var request = options();
        for (Image image : images) request.inputs.add(List.of(new ImageBytesPiece(image.mimeType(), image.bytes())));
        return call(request);
    }
    private List<double[]> call(EmbeddingRequest request) throws IOException {
        if (request.inputs.isEmpty()) return List.of();
        var provider = provider();
        var identity = provider.embeddingSpace(options());
        if (!space.equals(EmbeddingSpaces.canonical(new Space(identity.model(), identity.revision(), identity.dimension())))) throw new IOException("Embedding Provider space changed");
        var response = provider.embedding(request);
        if (response == null || response.data == null || response.data.size() != request.inputs.size()) throw new IOException("Embedding response count mismatch");
        double[][] ordered = new double[request.inputs.size()][];
        for (var row : response.data) {
            if (row == null || row.index < 0 || row.index >= ordered.length || ordered[row.index] != null) throw new IOException("Invalid embedding response index");
            ordered[row.index] = row.embedding;
        }
        if (response.usage != null) inputTokens.addAndGet(response.usage.prompt_tokens);
        return Vectors.batch(java.util.Arrays.asList(ordered), ordered.length, dimension);
    }
}
