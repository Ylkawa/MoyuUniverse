package com.nekoyu.Universe.AIChat.graph;

import com.nekoyu.Universe.Universe;
import com.nekoyu.Universe.API.UniverseChannel;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.Providers.LLMProvider.Embedding;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphRuntimeDiagnosticsTest {
    @TempDir Path temp;
    private final List<Path> cacheFiles = new ArrayList<>();
    private void render(GraphRuntime runtime, String icon) throws Exception {
        String xml = "<graph type='list'><line" + (icon == null ? "" : " icon='" + icon + "'") + ">正文始终保留</line></graph>";
        var images = runtime.decoupler().process(com.nekoyu.Universe.AIChat.XmlMarks.parse(xml));
        assertEquals(1, images.size());
        for (var field : images) UniverseChannel.releaseRepost(assertInstanceOf(ImageField.class, field).getUrl());
    }
    @Test void publicRuntimeDistinguishesSkippedMatchingAndWorkspaceIndependentCacheMisses() throws Exception {
        String id = "graph-diagnostics-" + UUID.randomUUID();
        String query = "new-query-" + UUID.randomUUID();
        var bundle = IconBundle.load(IconAssetSource.classpath(GraphRuntime.class.getClassLoader(), IconBundle.PREFIX));
        var space = bundle.index().descriptor().space();
        var provider = new ProviderIconEmbeddingProviderTest.Provider() {
            @Override public Embedding.Space embeddingSpace(EmbeddingRequest request) {
                return new Embedding.Space(space.model(), EmbeddingSpaceMigrationTest.legacy("ws-other", "#space=v1#res=1", space.dimension()).revision(), space.dimension());
            }
            @Override public EmbeddingResponse embedding(EmbeddingRequest request) {
                calls++;
                var result = new EmbeddingResponse(); var row = new EmbeddingResponse.Embedding();
                row.embedding = new double[space.dimension()]; row.embedding[0] = 1; row.index = 0; result.data.add(row);
                return result;
            }
        };
        var settings = new GraphSettings(); settings.embeddingProvider = id; settings.outputDirectory = temp.resolve("png").toString();
        String address = UniverseChannel.getOutboundHttpAddress(); UniverseChannel.setOutboundHttpAddress("http://127.0.0.1:2577");
        cacheFiles.add(new EmbeddingCache(Path.of("data/AIChat/graph-embeddings/text"), space).entryPath(query));
        try {
            Universe.Providers.put(id, provider);
            try (var runtime = GraphRuntime.open(settings)) {
                render(runtime, null); assertEquals(0, provider.calls);
                render(runtime, query); assertEquals(1, provider.calls);
                render(runtime, query); assertEquals(1, provider.calls);
            }
            settings.iconsEnabled = false;
            try (var runtime = GraphRuntime.open(settings)) { render(runtime, query); assertEquals(1, provider.calls); }
            settings.iconsEnabled = true; Universe.Providers.remove(id);
            try (var runtime = GraphRuntime.open(settings)) { render(runtime, query); assertEquals(1, provider.calls); }
            Universe.Providers.put(id, new ProviderIconEmbeddingProviderTest.Provider());
            try (var runtime = GraphRuntime.open(settings)) { render(runtime, query); assertEquals(1, provider.calls); }
            Universe.Providers.put(id, new ProviderIconEmbeddingProviderTest.Provider() {
                @Override public Embedding.Space embeddingSpace(EmbeddingRequest request) {
                    return new Embedding.Space(space.model(), "dashscope-independent-v1#space=v2#res=1", space.dimension());
                }
                @Override public EmbeddingResponse embedding(EmbeddingRequest request) { fail("Incompatible space must not invoke the model"); return null; }
            });
            try (var runtime = GraphRuntime.open(settings)) { render(runtime, query); }
            try (var runtime = GraphRuntime.open(settings, IconAssetSource.directory(temp.resolve("missing-bundle")))) { render(runtime, query); }
        } finally {
            Universe.Providers.remove(id); UniverseChannel.setOutboundHttpAddress(address);
            for (Path file : cacheFiles) Files.deleteIfExists(file);
        }
    }
}
