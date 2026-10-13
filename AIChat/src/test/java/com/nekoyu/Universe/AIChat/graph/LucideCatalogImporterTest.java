package com.nekoyu.Universe.AIChat.graph;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LucideCatalogImporterTest {
    @org.junit.jupiter.api.Test void manifestReloadChecksSourceAndPaths(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        var catalog = GraphTestFixtures.catalog(directory);
        var manifest = catalog.licensePath().getParent().resolve("catalog.json");
        assertEquals(catalog, new LucideCatalogImporter().loadManifest(manifest));
        Files.writeString(catalog.icons().get(0).svgPath(), "modified");
        assertThrows(java.io.IOException.class, () -> new LucideCatalogImporter().loadManifest(manifest));
    }
    @TempDir Path directory;
    @Test void preservesResourcesLicenseAndDeterministicManifest() throws Exception {
        Path source = GraphTestFixtures.source(directory.resolve("source"));
        var importer = new LucideCatalogImporter();
        IconCatalog catalog = importer.importDirectory(source, "test-1", directory.resolve("cache"));
        IconCatalog again = importer.importDirectory(source, "test-1", directory.resolve("cache"));
        assertEquals(catalog, again);
        assertEquals(Files.readString(source.resolve("LICENSE")), Files.readString(catalog.licensePath()));
        assertArrayEquals(Files.readAllBytes(source.resolve("icons/laptop.svg")), Files.readAllBytes(catalog.icons().get(0).svgPath()));
        var manifest = JsonParser.parseString(Files.readString(catalog.licensePath().resolveSibling("catalog.json"))).getAsJsonObject();
        assertEquals(catalog.digest(), manifest.get("digest").getAsString());
        assertEquals(2, manifest.getAsJsonArray("icons").size());
        var png = ImageIO.read(catalog.icons().get(0).previewPath().toFile());
        assertEquals(256, png.getWidth()); assertEquals(256, png.getHeight());
        assertEquals(Color.WHITE.getRGB(), png.getRGB(0, 0));
        long dark = 0;
        for (int x = 32; x < 224; x++) for (int y = 32; y < 224; y++) if ((png.getRGB(x, y) & 0xffffff) < 0x808080) dark++;
        assertTrue(dark > 500, "currentColor SVG must actually render visible black strokes");
        Files.writeString(source.resolve("icons/laptop.svg"), GraphTestFixtures.SVG.replace("width='16'", "width='15'"));
        assertNotEquals(catalog.digest(), importer.importDirectory(source, "test-1", directory.resolve("cache")).digest());
    }
    @Test void rejectsMissingLicenseAndVersionMismatch() throws Exception {
        Path source = GraphTestFixtures.source(directory.resolve("source"));
        assertThrows(java.io.IOException.class, () -> new LucideCatalogImporter().importDirectory(source, "other", directory.resolve("cache")));
        Files.delete(source.resolve("LICENSE"));
        assertThrows(java.io.IOException.class, () -> new LucideCatalogImporter().importDirectory(source, "test-1", directory.resolve("cache")));
    }
    @Test void rejectsExecutableAndExternalSvgContent() {
        SvgIconPreview renderer = new SvgIconPreview();
        for (String svg : java.util.List.of("<!DOCTYPE svg [<!ENTITY e SYSTEM 'file:///unavailable'>]>" + GraphTestFixtures.SVG,
                GraphTestFixtures.SVG.replace("</svg>", "<script>alert(1)</script></svg>"),
                GraphTestFixtures.SVG.replace("</svg>", "<image href='https://example.invalid/icon.png'/></svg>"),
                GraphTestFixtures.SVG.replace("<rect ", "<rect onclick='run()' "))) {
            assertThrows(java.io.IOException.class, () -> renderer.render(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
}
