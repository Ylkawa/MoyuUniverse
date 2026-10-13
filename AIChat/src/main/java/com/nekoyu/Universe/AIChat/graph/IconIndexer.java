package com.nekoyu.Universe.AIChat.graph;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Explicit, bounded indexing; nothing runs at plugin startup. */
public final class IconIndexer {
    private final IconEmbeddingProvider provider;
    private final IconIndex index;
    private final int batchSize;
    public IconIndexer(IconEmbeddingProvider provider, IconIndex index) { this(provider, index, 5); }
    public IconIndexer(IconEmbeddingProvider provider, IconIndex index, int batchSize) {
        this.provider = Objects.requireNonNull(provider); this.index = Objects.requireNonNull(index);
        if (batchSize < 1) throw new IllegalArgumentException("Batch size must be positive");
        this.batchSize = batchSize;
    }
    public int index(IconCatalog catalog) throws IOException {
        IndexDescriptor expected = IndexDescriptor.of(provider.space(), catalog);
        if (!expected.equals(index.descriptor())) throw new IOException("Index identity does not match provider/catalog");
        index.initialize();
        int written = 0;
        for (int start = 0; start < catalog.icons().size(); start += batchSize) {
            if (!expected.space().equals(provider.space())) throw new IOException("Embedding provider space changed during indexing");
            List<IconResource> batch = catalog.icons().subList(start, Math.min(start + batchSize, catalog.icons().size()));
            List<IconEmbeddingProvider.Image> images = new ArrayList<>();
            for (IconResource icon : batch) images.add(new IconEmbeddingProvider.Image(icon.id(), "image/png", Files.readAllBytes(icon.previewPath())));
            List<double[]> vectors = Vectors.batch(provider.embedImages(List.copyOf(images)), batch.size(), expected.space().dimension());
            List<IconIndex.IndexedIcon> entries = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) entries.add(new IconIndex.IndexedIcon(batch.get(i), vectors.get(i)));
            index.upsert(List.copyOf(entries));
            written += batch.size();
        }
        return written;
    }
}
