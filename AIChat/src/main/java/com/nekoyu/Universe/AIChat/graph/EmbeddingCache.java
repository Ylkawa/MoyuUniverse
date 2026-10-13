package com.nekoyu.Universe.AIChat.graph;

import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/** 磁盘记录长期保留，内存有界；同一 JVM 内相同键只计算一次。 */
public final class EmbeddingCache {
    @FunctionalInterface public interface Compute { double[] run() throws IOException; }
    private static final int MAGIC = 0x47454331;
    private static final ConcurrentHashMap<String, CompletableFuture<double[]>> PENDING = new ConcurrentHashMap<>();
    private final IconEmbeddingProvider.Space space;
    private final String identity;
    private final Path root;
    private final int memoryLimit;
    private final LinkedHashMap<String, double[]> memory = new LinkedHashMap<>(16, .75f, true);
    public EmbeddingCache(Path directory, IconEmbeddingProvider.Space space) { this(directory, space, 1024); }
    public EmbeddingCache(Path directory, IconEmbeddingProvider.Space space, int memoryLimit) {
        if (space.dimension() > 4096 || memoryLimit < 0) throw new IllegalArgumentException("Unsupported cache bounds");
        this.space = space; this.memoryLimit = memoryLimit;
        identity = IndexDescriptor.hash((space.model().length() + ":" + space.model() + space.revision().length()
                + ":" + space.revision() + ":" + space.dimension() + ":float32-l2-v1:exact-text-v1").getBytes(StandardCharsets.UTF_8));
        root = directory.toAbsolutePath().normalize().resolve(identity);
    }
    public Path entryPath(String input) {
        String key = IndexDescriptor.hash(input.getBytes(StandardCharsets.UTF_8));
        return root.resolve(key.substring(0, 2)).resolve(key + ".bin");
    }
    public double[] find(String input) {
        String key = IndexDescriptor.hash(input.getBytes(StandardCharsets.UTF_8));
        synchronized (memory) { double[] value = memory.get(key); if (value != null) return value.clone(); }
        return load(input);
    }
    public double[] get(String input, Compute compute) throws IOException {
        String key = IndexDescriptor.hash(input.getBytes(StandardCharsets.UTF_8));
        synchronized (memory) { double[] value = memory.get(key); if (value != null) return value.clone(); }
        String pendingKey = root + ":" + key;
        var future = new CompletableFuture<double[]>();
        var existing = PENDING.putIfAbsent(pendingKey, future);
        if (existing != null) {
            try { return existing.get().clone(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Embedding wait interrupted", e); }
            catch (ExecutionException e) { throw new IOException("Embedding computation failed", e.getCause()); }
        }
        try {
            double[] value = load(input);
            if (value == null) {
                float[] vector = Vectors.normalized(compute.run(), space.dimension());
                value = new double[vector.length];
                for (int i = 0; i < vector.length; i++) value[i] = vector[i];
                try { save(input, vector); }
                catch (IOException e) { LoggerFactory.getLogger(EmbeddingCache.class).warn("无法持久化图标向量缓存，当前结果继续使用：{}", e.toString()); }
            }
            synchronized (memory) {
                if (memoryLimit > 0) {
                    memory.put(key, value.clone());
                    while (memory.size() > memoryLimit) memory.remove(memory.keySet().iterator().next());
                }
            }
            future.complete(value); return value.clone();
        } catch (IOException | RuntimeException e) { future.completeExceptionally(e); throw e; }
        finally { PENDING.remove(pendingKey, future); }
    }
    private double[] load(String input) {
        Path file = entryPath(input);
        try {
            if (!Files.exists(file)) return null;
            if (Files.size(file) > space.dimension() * 4L + 1024) throw new IOException("Oversized cache record");
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length < 32) throw new IOException("Truncated cache record");
            byte[] data = Arrays.copyOf(bytes, bytes.length - 32);
            String checksum = java.util.HexFormat.of().formatHex(bytes, bytes.length - 32, bytes.length);
            if (!IndexDescriptor.hash(data).equals(checksum)) throw new IOException("Cache checksum mismatch");
            try (var stream = new DataInputStream(new ByteArrayInputStream(data))) {
                if (stream.readInt() != MAGIC || stream.readInt() != 1 || !stream.readUTF().equals(identity)
                        || !stream.readUTF().equals(IndexDescriptor.hash(input.getBytes(StandardCharsets.UTF_8)))
                        || stream.readInt() != space.dimension()) throw new IOException("Cache identity mismatch");
                double[] value = new double[space.dimension()];
                double norm = 0;
                for (int i = 0; i < value.length; i++) { value[i] = stream.readFloat(); norm += value[i] * value[i]; }
                if (stream.read() != -1 || !Double.isFinite(norm) || Math.abs(norm - 1) > 0.0001) throw new IOException("Invalid cache vector");
                return value;
            }
        } catch (IOException | RuntimeException e) {
            LoggerFactory.getLogger(EmbeddingCache.class).warn("图标向量缓存不可用，将按需重新计算：{}", file);
            return null;
        }
    }
    private void save(String input, float[] vector) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var stream = new DataOutputStream(buffer)) {
            stream.writeInt(MAGIC); stream.writeInt(1); stream.writeUTF(identity);
            stream.writeUTF(IndexDescriptor.hash(input.getBytes(StandardCharsets.UTF_8))); stream.writeInt(vector.length);
            for (float value : vector) stream.writeFloat(value);
        }
        byte[] data = buffer.toByteArray();
        buffer.writeBytes(java.util.HexFormat.of().parseHex(IndexDescriptor.hash(data)));
        AtomicFiles.write(entryPath(input), buffer.toByteArray());
    }
}
