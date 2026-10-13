package com.nekoyu.Universe.AIChat.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.List;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class IconBundleTest {
    @TempDir Path directory;
    @Test void verifiesClasspathAssetsAndRejectsPngAndVectorCorruption() throws Exception {
        var catalog = GraphTestFixtures.catalog(directory.resolve("catalog"));
        var provider = new GraphTestFixtures.Provider(); Path output = directory.resolve("bundle");
        var bundle = new IconBundleBuilder().build(catalog, provider, directory.resolve("images"), output);
        assertEquals(2, provider.imageCalls);
        new IconBundleBuilder().build(catalog, provider, directory.resolve("images"), output);
        assertEquals(2, provider.imageCalls);
        Path jar = directory.resolve("assets.jar");
        try (var stream = new JarOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(output)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                stream.putNextEntry(new JarEntry(IconBundle.PREFIX + output.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, stream); stream.closeEntry();
            }
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, null)) {
            assertEquals("jar", loader.getResource(IconBundle.PREFIX + "current.json").getProtocol());
            var fromJar = IconBundle.load(IconAssetSource.classpath(loader, IconBundle.PREFIX));
            assertEquals(bundle.index().descriptor(), fromJar.index().descriptor());
            assertEquals("lucide/laptop", fromJar.index().search(new double[]{1, 0}, 5).get(0).resource().id());
            assertArrayEquals(catalog.icons().get(0).assets().read(catalog.icons().get(0).previewName(), 1024 * 1024),
                    fromJar.catalog().icons().get(0).assets().read(fromJar.catalog().icons().get(0).previewName(), 1024 * 1024));
        }
        Path vectorFile;
        try (var files = Files.walk(output)) { vectorFile = files.filter(p -> p.getFileName().toString().equals("vectors.bin")).findFirst().orElseThrow(); }
        byte[] bytes = Files.readAllBytes(vectorFile); bytes[bytes.length - 1] ^= 1; Files.write(vectorFile, bytes);
        assertThrows(IOException.class, () -> IconBundle.load(IconAssetSource.directory(output)));
    }
    @Test void resumesAfterInterruptionAndDoesNotReplacePublishedPointerOnFailure() throws Exception {
        var catalog = GraphTestFixtures.catalog(directory.resolve("catalog"));
        var interrupted = new GraphTestFixtures.Provider() {
            @Override public List<double[]> embedImages(List<Image> images) throws IOException {
                if (imageCalls == 1) throw new IOException("interrupted");
                return super.embedImages(images);
            }
        };
        Path output = directory.resolve("bundle");
        assertThrows(IOException.class, () -> new IconBundleBuilder().build(catalog, interrupted, directory.resolve("images"), output));
        assertFalse(Files.exists(output.resolve("current.json")));
        var resumed = new GraphTestFixtures.Provider();
        new IconBundleBuilder().build(catalog, resumed, directory.resolve("images"), output);
        assertEquals(1, resumed.imageCalls);
        byte[] pointer = Files.readAllBytes(output.resolve("current.json"));
        var failed = new GraphTestFixtures.Provider() {
            @Override public List<double[]> embedImages(List<Image> images) throws IOException { throw new IOException("offline"); }
        };
        assertThrows(IOException.class, () -> new IconBundleBuilder().build(catalog, failed, directory.resolve("different-cache"), output));
        assertArrayEquals(pointer, Files.readAllBytes(output.resolve("current.json")));
        IconBundle.load(IconAssetSource.directory(output));
    }
    @Test void supportsPrefixNamesAndDetectsPreviewTampering() throws Exception {
        Path source = GraphTestFixtures.source(directory.resolve("source"));
        Files.writeString(source.resolve("icons/laptop-a.svg"), GraphTestFixtures.SVG);
        var catalog = new LucideCatalogImporter().importDirectory(source, "test-1", directory.resolve("prepared"));
        assertEquals(List.of("laptop", "laptop-a", "monitor"), catalog.icons().stream().map(IconResource::name).toList());
        Files.write(catalog.icons().get(0).previewPath(), new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> new LucideCatalogImporter().loadManifest(catalog.licensePath().resolveSibling("catalog.json")));
    }
    @Test void missingBundleStillRegistersAllSixRenderers() throws Exception {
        var settings = new GraphSettings();
        try (var runtime = GraphRuntime.open(settings, IconAssetSource.directory(directory))) {
            assertNotNull(runtime.decoupler());
            assertTrue(runtime.skill().systemPrompt.contains("省略所有 icon"));
        }
    }
}
