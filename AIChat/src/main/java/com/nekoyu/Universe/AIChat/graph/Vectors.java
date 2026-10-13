package com.nekoyu.Universe.AIChat.graph;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class Vectors {
    static float[] normalized(double[] vector, int dimension) throws IOException {
        if (vector == null || vector.length != dimension) throw new IOException("Embedding dimension mismatch");
        double norm = 0;
        for (double value : vector) {
            if (!Double.isFinite(value) || !Float.isFinite((float) value)) throw new IOException("Non-finite embedding");
            norm = Math.hypot(norm, value);
        }
        if (norm == 0) throw new IOException("Zero embedding");
        float[] output = new float[dimension];
        for (int i = 0; i < dimension; i++) output[i] = (float) (vector[i] / norm);
        return output;
    }
    private Vectors() {}
    static List<double[]> batch(List<double[]> vectors, int count, int dimension) throws IOException {
        if (vectors == null || vectors.size() != count) throw new IOException("Embedding count mismatch: expected " + count);
        List<double[]> checked = new ArrayList<>();
        for (double[] vector : vectors) { floats(vector, dimension); checked.add(vector.clone()); }
        return List.copyOf(checked);
    }
    static List<Float> floats(double[] vector, int dimension) throws IOException {
        if (vector == null || vector.length != dimension) throw new IOException("Embedding dimension mismatch: expected " + dimension);
        List<Float> values = new ArrayList<>();
        boolean nonzero = false;
        for (double value : vector) {
            float converted = (float) value;
            if (!Double.isFinite(value) || !Float.isFinite(converted)) throw new IOException("Embedding contains non-finite values");
            nonzero |= converted != 0;
            values.add(converted);
        }
        if (!nonzero) throw new IOException("Zero embedding cannot be used for cosine similarity");
        return values;
    }
}
