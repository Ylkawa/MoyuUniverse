package com.nekoyu.Universe.AIChat.graph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GraphTestFixtures {
    record IndexedIcon(IconResource resource, double[] vector) {}
    static int fill(IconEmbeddingProvider provider, MemoryIndex index, IconCatalog catalog) throws IOException {
        if (!IndexDescriptor.of(provider.space(), catalog).equals(index.descriptor())) throw new IOException("Index identity differs");
        for (IconResource icon : catalog.icons()) {
            var vector = provider.embedImages(List.of(new IconEmbeddingProvider.Image(icon.id(), "image/png", Files.readAllBytes(icon.previewPath())))).get(0);
            index.points.put(icon.id(), new IndexedIcon(icon, vector));
        }
        return catalog.icons().size();
    }
    static final String SVG = "<svg xmlns='http://www.w3.org/2000/svg' width='24' height='24' viewBox='0 0 24 24' "
            + "fill='none' stroke='currentColor' stroke-width='2'><rect x='4' y='4' width='16' height='12'/><path d='M2 20h20'/></svg>";
    static Path source(Path directory) throws IOException {
        Files.createDirectories(directory.resolve("icons"));
        Files.writeString(directory.resolve("LICENSE"), "Synthetic test fixture; not a distributed Lucide icon");
        Files.writeString(directory.resolve("package.json"), "{\"name\":\"lucide-static\",\"version\":\"test-1\"}");
        Files.writeString(directory.resolve("icons/laptop.svg"), SVG);
        Files.writeString(directory.resolve("icons/monitor.svg"), SVG.replace("M2 20h20", "M12 16v4"));
        return directory;
    }
    static IconCatalog catalog(Path directory) throws IOException {
        return new LucideCatalogImporter().importDirectory(source(directory.resolve("source")), "test-1", directory.resolve("cache"));
    }
    static class Provider implements IconEmbeddingProvider {
        Space identity = new Space("test-multimodal", "1", 2);
        int textCalls, imageCalls;
        List<String> texts = List.of();
        List<double[]> override;
        @Override public Space space() { return identity; }
        @Override public List<double[]> embedTexts(List<String> texts) {
            textCalls++; this.texts = List.copyOf(texts);
            return override != null ? override : texts.stream().map(t -> t.equals("laptop") ? new double[]{1, 0} : new double[]{0, 1}).toList();
        }
        @Override public List<double[]> embedImages(List<Image> images) throws IOException {
            imageCalls++;
            return images.stream().map(i -> i.id().equals("lucide/laptop") ? new double[]{1, 0} : new double[]{0, 1}).toList();
        }
    }
    static class MemoryIndex implements IconIndex {
        private final IndexDescriptor descriptor;
        final Map<String, IndexedIcon> points = new LinkedHashMap<>();
        int searches, verifications;
        IOException failure;
        List<IconCandidate> override;
        MemoryIndex(IndexDescriptor descriptor) { this.descriptor = descriptor; }
        @Override public IndexDescriptor descriptor() { return descriptor; }
        @Override public void verify() throws IOException { verifications++; if (failure != null) throw failure; }
        @Override public List<IconCandidate> search(double[] vector, int limit) throws IOException {
            searches++; if (failure != null) throw failure;
            if (override != null) return override;
            List<IconCandidate> candidates = new ArrayList<>();
            for (IndexedIcon value : points.values()) {
                double[] image = value.vector();
                double score = (vector[0] * image[0] + vector[1] * image[1])
                        / (Math.hypot(vector[0], vector[1]) * Math.hypot(image[0], image[1]));
                candidates.add(new IconCandidate(value.resource(), score));
            }
            return candidates;
        }
    }
}
