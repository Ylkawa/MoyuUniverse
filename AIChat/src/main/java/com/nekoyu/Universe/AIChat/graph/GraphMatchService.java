package com.nekoyu.Universe.AIChat.graph;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GraphMatchService {
    public static final int DEFAULT_CANDIDATES = 5;
    private final IconEmbeddingProvider provider;
    private final IconIndex index;
    private final IndexDescriptor descriptor;
    private final Map<String, IconResource> resources;
    private final int candidateLimit;

    public GraphMatchService(IconEmbeddingProvider provider, IconIndex index, IconCatalog catalog) {
        this(provider, index, catalog, DEFAULT_CANDIDATES);
    }
    public GraphMatchService(IconEmbeddingProvider provider, IconIndex index, IconCatalog catalog, int candidateLimit) {
        this.provider = Objects.requireNonNull(provider); this.index = Objects.requireNonNull(index);
        this.descriptor = IndexDescriptor.of(provider.space(), catalog);
        if (!descriptor.equals(index.descriptor())) throw new IllegalArgumentException("Index identity does not match provider/catalog");
        if (candidateLimit < 1) throw new IllegalArgumentException("Candidate limit must be positive");
        this.candidateLimit = candidateLimit;
        this.resources = new LinkedHashMap<>();
        catalog.icons().forEach(icon -> resources.put(icon.id(), icon));
    }
    public ResolvedGraph resolve(GraphSpec graph) throws IOException {
        Objects.requireNonNull(graph);
        List<GraphSpec.IconSlot> slots = graph.iconSlots();
        if (slots.isEmpty()) return new ResolvedGraph(graph, List.of());
        if (!descriptor.space().equals(provider.space())) throw new IOException("Embedding provider space changed; rebuild the index");
        index.verify();
        Map<String, List<IconCandidate>> queries = new LinkedHashMap<>();
        for (GraphSpec.IconSlot slot : slots) queries.putIfAbsent(slot.query(), List.of());
        List<String> texts = List.copyOf(queries.keySet());
        List<double[]> vectors = Vectors.batch(provider.embedTexts(texts), texts.size(), descriptor.space().dimension());
        for (int i = 0; i < texts.size(); i++) {
            List<IconCandidate> found = index.search(vectors.get(i), candidateLimit);
            if (found == null) throw new IOException("Index returned null candidates");
            Map<String, IconCandidate> unique = new LinkedHashMap<>();
            for (IconCandidate candidate : found) {
                if (candidate == null || !candidate.resource().equals(resources.get(candidate.resource().id())))
                    throw new IOException("Index returned a resource outside this catalog");
                unique.merge(candidate.resource().id(), candidate, (a, b) -> a.score() >= b.score() ? a : b);
            }
            queries.put(texts.get(i), unique.values().stream()
                    .sorted(Comparator.comparingDouble(IconCandidate::score).reversed().thenComparing(c -> c.resource().id()))
                    .limit(candidateLimit).toList());
        }
        List<ResolvedGraph.IconMatch> matches = new ArrayList<>();
        for (GraphSpec.IconSlot slot : slots) matches.add(new ResolvedGraph.IconMatch(slot.key(), slot.query(), queries.get(slot.query())));
        return new ResolvedGraph(graph, matches);
    }
}
