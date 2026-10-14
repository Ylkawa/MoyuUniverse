package com.nekoyu.Universe.AIChat.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingSpaceMigrationTest {
    @TempDir Path temp;
    static IconEmbeddingProvider.Space legacy(String workspace, String suffix, int dimension) {
        return new IconEmbeddingProvider.Space("tongyi-embedding-vision-plus-2026-03-06",
                "dashscope-independent-v1@https://" + workspace + ".cn-beijing.maas.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding" + suffix, dimension);
    }
    @Test void workspaceAliasesPreserveEveryEncodingParameter() {
        var first = legacy("llm-original", "#space=v1#res=1", 1152);
        var second = legacy("ws-other", "#space=v1#res=1", 1152);
        assertEquals(EmbeddingSpaces.canonical(first), EmbeddingSpaces.canonical(second));
        assertEquals("dashscope-independent-v1#space=v1#res=1", EmbeddingSpaces.canonical(first).revision());
        assertNotEquals(EmbeddingSpaces.canonical(first), EmbeddingSpaces.canonical(legacy("ws-other", "#space=v2#res=1", 1152)));
        assertNotEquals(EmbeddingSpaces.canonical(first), EmbeddingSpaces.canonical(legacy("ws-other", "#space=v1#res=0", 1152)));
        assertNotEquals(EmbeddingSpaces.canonical(first), EmbeddingSpaces.canonical(legacy("ws-other", "#space=v1#res=1", 512)));
        var fusion = new IconEmbeddingProvider.Space(first.model(), first.revision().replace("independent", "fused"), first.dimension());
        assertNotEquals(EmbeddingSpaces.canonical(first), EmbeddingSpaces.canonical(fusion));
        var unknown = new IconEmbeddingProvider.Space("custom-model", first.revision(), first.dimension());
        assertSame(unknown, EmbeddingSpaces.canonical(unknown));
    }
    @Test void oldPackageAndTextCacheAreReusedWithoutComputingOrRewritingTheBundle() throws Exception {
        var provider = new GraphTestFixtures.Provider();
        provider.identity = legacy("llm-original", "#space=v1#res=1", 2);
        var catalog = GraphTestFixtures.catalog(temp.resolve("catalog"));
        Path output = temp.resolve("bundle");
        new IconBundleBuilder().build(catalog, provider, temp.resolve("images"), output);
        byte[] pointer = Files.readAllBytes(output.resolve("current.json"));
        Path vectors;
        try (var paths = Files.walk(output)) { vectors = paths.filter(p -> p.getFileName().toString().equals("vectors.bin")).findFirst().orElseThrow(); }
        byte[] original = Files.readAllBytes(vectors);
        var loaded = IconBundle.load(IconAssetSource.directory(output));
        assertEquals(provider.space(), loaded.storedSpace());
        assertEquals(EmbeddingSpaces.canonical(provider.space()), loaded.index().descriptor().space());
        assertEquals("lucide/laptop", loaded.index().search(new double[]{1, 0}, 5).get(0).resource().id());
        var oldCache = new EmbeddingCache(temp.resolve("text"), provider.space());
        oldCache.get("laptop", () -> new double[]{1, 0});
        var canonical = loaded.index().descriptor().space();
        var cache = new CachedEmbeddingProvider(canonical, temp.resolve("text"), () -> { throw new java.io.IOException("No remote request allowed"); }, loaded.storedSpace());
        assertArrayEquals(new double[]{1, 0}, cache.embedTexts(List.of("laptop")).get(0));
        assertTrue(Files.exists(new EmbeddingCache(temp.resolve("text"), canonical).entryPath("laptop")));
        assertTrue(Files.exists(oldCache.entryPath("laptop")));
        assertArrayEquals(pointer, Files.readAllBytes(output.resolve("current.json")));
        assertArrayEquals(original, Files.readAllBytes(vectors));
        assertThrows(IllegalArgumentException.class, () -> new CachedEmbeddingProvider(
                legacy("different", "#space=v2#res=1", 2), temp.resolve("text"), () -> provider, loaded.storedSpace()));
    }
}
