package com.nekoyu.Universe.AIChat.graph;

import java.io.IOException;
import java.util.List;

public interface IconIndex {
    IndexDescriptor descriptor();
    void verify() throws IOException;
    List<IconCandidate> search(double[] vector, int limit) throws IOException;
}
