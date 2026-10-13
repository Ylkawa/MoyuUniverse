package com.nekoyu.Universe.AIChat.graph;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 图标查询先查持久缓存；底层 HTTP provider 延迟创建，缓存命中不要求密钥。 */
public final class CachedEmbeddingProvider implements IconEmbeddingProvider {
    @FunctionalInterface public interface Factory { IconEmbeddingProvider open() throws IOException; }
    private final Space space;
    private final Factory factory;
    private final EmbeddingCache cache;
    private volatile IconEmbeddingProvider delegate;
    public CachedEmbeddingProvider(Space space, Path directory, Factory factory) {
        this.space = space; this.factory = factory; cache = new EmbeddingCache(directory, space);
    }
    @Override public Space space() { return space; }
    private synchronized IconEmbeddingProvider delegate() throws IOException {
        if (delegate == null) {
            var provider = factory.open();
            if (!space.equals(provider.space())) throw new IOException("Embedding space differs from bundled index");
            delegate = provider;
        }
        return delegate;
    }
    private double[] text(String value) throws IOException {
        return cache.get(value, () -> {
            var provider = delegate();
            return Vectors.batch(provider.embedTexts(List.of(value)), 1, space.dimension()).get(0);
        });
    }
    @Override public List<double[]> embedTexts(List<String> texts) throws IOException {
        var result = new ArrayList<double[]>();
        for (String value : texts) result.add(text(value));
        return List.copyOf(result);
    }
    @Override public List<TextResult> textResults(List<String> texts) {
        var results = new ArrayList<TextResult>();
        for (String value : texts) {
            try { results.add(new TextResult(text(value))); }
            catch (IOException | RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(CachedEmbeddingProvider.class).warn("图标向量不可用，保留正文继续绘制：{}", e.toString());
                results.add(new TextResult(null));
            }
        }
        return List.copyOf(results);
    }
    @Override public List<double[]> embedImages(List<Image> images) throws IOException { throw new IOException("Image embedding is a prebuild operation"); }
}
