package com.nekoyu.Universe.AIChat.graph;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ResolvedGraph(GraphSpec graph, List<IconMatch> matches) {
    public ResolvedGraph { Objects.requireNonNull(graph); matches = List.copyOf(matches); }
    public enum Status { CANDIDATES, UNMATCHED }
    public record IconMatch(String slot, String query, List<IconCandidate> candidates) {
        public IconMatch { Objects.requireNonNull(slot); Objects.requireNonNull(query); candidates = List.copyOf(candidates); }
        public Status status() { return candidates.isEmpty() ? Status.UNMATCHED : Status.CANDIDATES; }
        public Optional<IconCandidate> preferred() { return candidates.stream().findFirst(); }
    }
}
