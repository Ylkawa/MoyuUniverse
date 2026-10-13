package com.nekoyu.Universe.AIChat.graph;

import com.nekoyu.Universe.Universe;
import com.nekoyu.Universe.API.Providers.LLMProvider.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderIconEmbeddingProviderTest {
    final String id = "graph-test-" + UUID.randomUUID();
    @TempDir Path directory;
    @AfterEach void cleanup() { Universe.Providers.remove(id); }
    static class Provider extends LLMProvider implements Embedding {
        final IconEmbeddingProvider.Space expected = new GraphTestFixtures.Provider().space();
        EmbeddingRequest last;
        int calls;
        String revision = expected.revision();
        @Override public Embedding.Space embeddingSpace(EmbeddingRequest request) {
            return new Embedding.Space(expected.model(), revision, expected.dimension());
        }
        @Override public EmbeddingResponse embedding(EmbeddingRequest request) {
            last = request; calls++;
            var response = new EmbeddingResponse(); response.model = expected.model(); response.usage.prompt_tokens = 7;
            for (int i = request.inputs.size() - 1; i >= 0; i--) {
                var row = new EmbeddingResponse.Embedding(); row.index = i; row.embedding = new double[]{1, i + 1}; response.data.add(row);
            }
            return response;
        }
        @Override public CompletionsResponse completions(String model, List<Message> messages, List<LLMFunction> tools, CompletionsRequest options, BufferCallback callback) { throw new UnsupportedOperationException(); }
    }
    ProviderIconEmbeddingProvider bridge() throws IOException { return new ProviderIconEmbeddingProvider(id, new GraphTestFixtures.Provider().space().model(), 2, "v1"); }
    @Test void callsRegisteredCapabilityWithNeutralTextAndImageBytes() throws Exception {
        var provider = new Provider(); Universe.Providers.put(id, provider); var bridge = bridge();
        var vectors = bridge.embedTexts(List.of("Laptop ", "笔记本电脑"));
        assertEquals(2, vectors.size()); assertArrayEquals(new double[]{1, 1}, vectors.get(0)); assertArrayEquals(new double[]{1, 2}, vectors.get(1));
        assertEquals("Laptop ", ((TextPiece) provider.last.inputs.get(0).get(0)).getText());
        assertFalse(provider.last.enableFusion); assertEquals(2, provider.last.dimension); assertEquals("v1", provider.last.spaceVersion);
        bridge.embedImages(List.of(new IconEmbeddingProvider.Image("a", "image/png", new byte[]{1, 2})));
        assertInstanceOf(ImageBytesPiece.class, provider.last.inputs.get(0).get(0));
        assertTrue(provider.last.message.isEmpty()); assertEquals(14, bridge.inputTokens());
    }
    @Test void rejectsMissingProviderAndChangedSpaceBeforeModelCall() throws Exception {
        assertThrows(IOException.class, this::bridge);
        Universe.Providers.put(id, new Object()); assertThrows(IOException.class, this::bridge);
        var provider = new Provider(); Universe.Providers.put(id, provider); var bridge = bridge();
        provider.revision = "changed"; assertThrows(IOException.class, () -> bridge.embedTexts(List.of("a"))); assertEquals(0, provider.calls);
    }
    @Test void cachedResultsStillWorkWhenRegisteredChannelCannotCalculate() throws Exception {
        var provider = new Provider(); Universe.Providers.put(id, provider); var bridge = bridge();
        var cached = new CachedEmbeddingProvider(bridge.space(), directory.resolve("cache"), () -> bridge);
        var first = cached.embedTexts(List.of("exact text")); assertEquals(1, provider.calls);
        Universe.Providers.remove(id);
        var reopened = new CachedEmbeddingProvider(bridge.space(), directory.resolve("cache"), () -> bridge);
        assertArrayEquals(first.get(0), reopened.embedTexts(List.of("exact text")).get(0));
        assertFalse(reopened.textResults(List.of("new text")).get(0).available());
    }
    @Test void invalidProviderResponseDoesNotReachTheIndex() throws Exception {
        var provider = new Provider() {
            @Override public EmbeddingResponse embedding(EmbeddingRequest request) {
                var response = super.embedding(request); response.data.get(0).index = 0; return response;
            }
        };
        Universe.Providers.put(id, provider);
        assertThrows(IOException.class, () -> bridge().embedTexts(List.of("a", "b")));
    }
    @Test void runtimeAllowsProviderRegistrationAfterGraphInitialization() throws Exception {
        var fixture = new GraphTestFixtures.Provider(); var catalog = GraphTestFixtures.catalog(directory.resolve("catalog"));
        Path bundleDirectory = directory.resolve("bundle"); new IconBundleBuilder().build(catalog, fixture, directory.resolve("images"), bundleDirectory);
        var settings = new GraphSettings(); settings.embeddingProvider = id; settings.embeddingModel = fixture.space().model(); settings.embeddingDimension = 2;
        settings.outputDirectory = directory.resolve("rendered").toString();
        var provider = new Provider(); String query = "runtime-" + UUID.randomUUID();
        com.nekoyu.Universe.API.UniverseChannel.setOutboundHttpAddress("http://127.0.0.1:2577");
        try (var runtime = GraphRuntime.open(settings, IconAssetSource.directory(bundleDirectory))) {
            Universe.Providers.put(id, provider);
            var result = runtime.decoupler().process(com.nekoyu.Universe.AIChat.XmlMarks.parse("<graph type='list'><line icon='" + query + "'>正文完整</line></graph>"));
            assertInstanceOf(com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField.class, result.getFirst());
            assertEquals(1, provider.calls);
        } finally {
            Files.deleteIfExists(new EmbeddingCache(Path.of("data/AIChat/graph-embeddings/text"), fixture.space()).entryPath(query));
        }
    }
}
