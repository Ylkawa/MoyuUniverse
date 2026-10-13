package com.nekoyu.Universe.AIChat.graph;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** 只读归一化 float32 矩阵，搜索只保留 Top K，不创建完整候选列表。 */
public final class LocalIconIndex implements IconIndex {
    private final IndexDescriptor descriptor;
    private final List<IconResource> icons;
    private final float[] matrix;
    private boolean verified;
    private record Hit(int row, double score, String id) {}
    private static final Comparator<Hit> BEST = Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::id);
    public LocalIconIndex(IndexDescriptor descriptor, List<IconResource> icons, float[] matrix) throws IOException {
        this.descriptor = descriptor; this.icons = List.copyOf(icons); this.matrix = matrix.clone();
        verify();
    }
    /** 包加载器转交已验证矩阵的所有权，避免加载时复制整库。 */
    static LocalIconIndex owned(IndexDescriptor descriptor, List<IconResource> icons, float[] matrix) throws IOException {
        return new LocalIconIndex(descriptor, icons, matrix, true);
    }
    private LocalIconIndex(IndexDescriptor descriptor, List<IconResource> icons, float[] matrix, boolean owned) throws IOException {
        this.descriptor = descriptor; this.icons = List.copyOf(icons); this.matrix = matrix; verify();
    }
    @Override public IndexDescriptor descriptor() { return descriptor; }
    @Override public void verify() throws IOException {
        if (verified) return;
        int dimension = descriptor.space().dimension();
        if (icons.isEmpty() || dimension <= 0 || (long) icons.size() * dimension != matrix.length) throw new IOException("Incomplete icon vectors");
        var ids = new java.util.HashSet<String>();
        for (int row = 0; row < icons.size(); row++) {
            if (!ids.add(icons.get(row).id()) || !icons.get(row).library().equals(descriptor.library())
                    || !icons.get(row).libraryVersion().equals(descriptor.libraryVersion())) throw new IOException("Invalid icon identity");
            double norm = 0;
            for (int col = 0; col < dimension; col++) {
                float value = matrix[row * dimension + col];
                if (!Float.isFinite(value)) throw new IOException("Non-finite icon vector");
                norm += (double) value * value;
            }
            if (Math.abs(norm - 1) > 0.0001) throw new IOException("Icon vector must be normalized");
        }
        verified = true;
    }
    @Override public List<IconCandidate> search(double[] vector, int limit) throws IOException {
        if (limit < 1) throw new IllegalArgumentException("Positive candidate limit required");
        float[] query = Vectors.normalized(vector, descriptor.space().dimension());
        int count = Math.min(limit, icons.size());
        var heap = new PriorityQueue<Hit>(count, BEST.reversed());
        for (int row = 0; row < icons.size(); row++) {
            double score = 0;
            for (int col = 0; col < query.length; col++) score += (double) matrix[row * query.length + col] * query[col];
            var hit = new Hit(row, Math.max(-1, Math.min(1, score)), icons.get(row).id());
            if (heap.size() < count) heap.add(hit);
            else if (BEST.compare(hit, heap.peek()) < 0) { heap.remove(); heap.add(hit); }
        }
        var sorted = new ArrayList<>(heap); sorted.sort(BEST);
        return sorted.stream().map(hit -> new IconCandidate(icons.get(hit.row()), hit.score())).toList();
    }
}
