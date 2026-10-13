package com.nekoyu.Universe.AIChat.graph;

import java.io.IOException;
import java.util.List;

public interface IconIndex {
    record IndexedIcon(IconResource resource, double[] vector) {
        public IndexedIcon { vector = vector.clone(); }
        @Override public double[] vector() { return vector.clone(); }
    }
    IndexDescriptor descriptor();
    /** Explicit setup only; matching never creates an empty collection as a side effect. */
    void initialize() throws IOException;
    void verify() throws IOException;
    void upsert(List<IndexedIcon> icons) throws IOException;
    List<IconCandidate> search(double[] vector, int limit) throws IOException;
}
