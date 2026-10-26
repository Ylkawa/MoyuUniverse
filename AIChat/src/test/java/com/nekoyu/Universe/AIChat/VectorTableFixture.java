package com.nekoyu.Universe.AIChat;

import com.google.gson.*;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.util.*;

/** In-memory storage boundary for testing fact classification independently of JDBC and providers. */
public class VectorTableFixture extends PgVectorTable {
    public record Stored(UUID id, List<Float> embedding, JsonObject payload) {}
    public final Map<UUID, Stored> points = new LinkedHashMap<>();
    public long lastDocumentId;
    public VectorTableFixture() { super(new HikariDataSource(), "test.facts", 2); }
    protected void beforeWrite() throws IOException {}
    @Override public void initialize() {}
    @Override public List<Point> search(List<Float> vector, String predicate, List<?> parameters, int limit, double minimum) {
        long now = ((Number) parameters.get(0)).longValue();
        return points.values().stream().filter(p -> p.payload().get("ttl").getAsLong() >= now).limit(limit)
                .map(p -> new Point(p.id(), p.payload().deepCopy(), 0.9)).toList();
    }
    @Override public List<Point> list(String predicate, List<?> parameters, String order, int limit) {
        long id = ((Number) parameters.get(0)).longValue();
        lastDocumentId = id;
        return points.values().stream().filter(p -> p.payload().get("document_id").getAsLong() == id
                        || (p.payload().has("document_ids") && p.payload().getAsJsonArray("document_ids").asList().stream().anyMatch(v -> v.getAsLong() == id)))
                .map(p -> new Point(p.id(), p.payload().deepCopy(), 0)).toList();
    }
    @Override public void upsert(UUID id, List<Float> embedding, JsonObject payload) throws IOException {
        beforeWrite();
        points.put(id, new Stored(id, List.copyOf(embedding), payload.deepCopy()));
    }
    @Override public void payload(UUID id, JsonObject patch) throws IOException {
        beforeWrite();
        points.computeIfPresent(id, (key, old) -> {
            JsonObject payload = old.payload().deepCopy();
            patch.entrySet().forEach(e -> payload.add(e.getKey(), e.getValue().deepCopy()));
            return new Stored(id, old.embedding(), payload);
        });
    }
    @Override public void delete(UUID id) throws IOException { beforeWrite(); points.remove(id); }
}
