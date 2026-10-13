package com.nekoyu.Universe.AIChat.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalIconIndexTest {
    @TempDir Path directory;
    @Test void matchesIndependentCosineReferenceAndStableTies() throws Exception {
        var catalog = GraphTestFixtures.catalog(directory);
        var source = catalog.icons().get(0);
        var icons = new ArrayList<IconResource>();
        var random = new Random(913);
        var vectors = new ArrayList<double[]>();
        float[] matrix = new float[40 * 2];
        for (int i = 0; i < 40; i++) {
            String name = String.format("icon-%02d", i);
            icons.add(new IconResource("lucide/" + name, name, "lucide", catalog.version(), source.sha256(),
                    source.svgName(), source.previewName(), source.previewSha256(), source.assets()));
            double[] vector = i < 3 ? new double[]{1, 0} : new double[]{random.nextDouble(), random.nextDouble()};
            vectors.add(vector); System.arraycopy(Vectors.normalized(vector, 2), 0, matrix, i * 2, 2);
        }
        var index = new LocalIconIndex(IndexDescriptor.of(new GraphTestFixtures.Provider().space(), catalog), icons, matrix);
        var result = index.search(new double[]{1, 0}, 5);
        var reference = new ArrayList<IconCandidate>();
        for (int i = 0; i < vectors.size(); i++) {
            double[] v = vectors.get(i);
            reference.add(new IconCandidate(icons.get(i), v[0] / Math.hypot(v[0], v[1])));
        }
        reference.sort(Comparator.comparingDouble(IconCandidate::score).reversed().thenComparing(c -> c.resource().id()));
        assertEquals(reference.subList(0, 5).stream().map(c -> c.resource().id()).toList(), result.stream().map(c -> c.resource().id()).toList());
        for (int i = 0; i < 5; i++) assertEquals(reference.get(i).score(), result.get(i).score(), 1e-6);
        matrix[0] = Float.NaN; assertEquals(1, index.search(new double[]{1, 0}, 1).get(0).score());
        assertThrows(java.io.IOException.class, () -> index.search(new double[]{0, 0}, 5));
        assertThrows(java.io.IOException.class, () -> index.search(new double[]{1}, 5));
        assertThrows(java.io.IOException.class, () -> index.search(new double[]{Double.NaN, 0}, 5));
    }
    @Test void rejectsMissingNonfiniteAndUnnormalizedRows() throws Exception {
        var catalog = GraphTestFixtures.catalog(directory);
        var descriptor = IndexDescriptor.of(new GraphTestFixtures.Provider().space(), catalog);
        for (float[] matrix : List.of(new float[]{1, 0}, new float[]{1, 0, 0, 0}, new float[]{1, 0, Float.NaN, 1}, new float[]{2, 0, 0, 1}))
            assertThrows(java.io.IOException.class, () -> new LocalIconIndex(descriptor, catalog.icons(), matrix));
    }
}
