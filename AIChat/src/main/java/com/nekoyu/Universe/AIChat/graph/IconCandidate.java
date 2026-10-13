package com.nekoyu.Universe.AIChat.graph;

import java.util.Objects;

public record IconCandidate(IconResource resource, double score) {
    public IconCandidate {
        Objects.requireNonNull(resource);
        if (!Double.isFinite(score)) throw new IllegalArgumentException("Non-finite similarity score");
    }
}
