package com.nekoyu.Universe.AIChat;

import com.google.gson.JsonObject;
import com.nekoyu.Universe.AIChat.leading.MemoryStore;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.Providers.LLMProvider.Embedding;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.EmbeddingRequest;
import javax.sql.DataSource;
import java.io.IOException;
import java.util.*;
import static com.nekoyu.Universe.Utils.Time.formatTimestamp;

public class Memory implements MemoryStore {
    private final Embedding provider;
    private final PgVectorTable table;
    private final int dimension;
    private static final String VISIBLE = "COALESCE((payload->>'confidence')::float8, -1) >= 0";

    public Memory(DataSource dataSource, Config.Memory config, Embedding provider) throws IOException {
        this(provider, new PgVectorTable(dataSource, config.table, config.dimension), config.dimension);
        table.initialize();
    }

    Memory(Embedding provider, PgVectorTable table, int dimension) {
        this.provider = Objects.requireNonNull(provider);
        this.table = table;
        this.dimension = dimension;
    }

    public List<Item> query(List<Float> queryVector, List<String> locationIds) {
        List<String> locations = locations(locationIds);
        List<Object> parameters = new ArrayList<>(locations);
        try {
            return table.search(queryVector, VISIBLE + locationPredicate(locations), parameters, 20, 0.6)
                    .stream().map(Memory::item).sorted(RETRIEVAL_ORDER).limit(10).toList();
        } catch (IOException e) { throw new IllegalStateException("Memory search failed", e); }
    }

    public void insert(List<Item> items) {
        try {
            for (Item item : items) {
                if (item.id == null) item.id = UUID.randomUUID();
                long now = System.currentTimeMillis();
                if (item.createAt == 0) item.createAt = now;
                item.updateAt = now;
                table.upsert(item.id, vector(embedding(item.content)), payload(item));
            }
        } catch (IOException e) { throw new IllegalStateException("Memory insert failed", e); }
    }

    public void update(UUID uuid, float confidence, float importance, String content) {
        JsonObject patch = new JsonObject();
        patch.addProperty("update_at", System.currentTimeMillis());
        patch.addProperty("confidence", confidence);
        patch.addProperty("importance", importance);
        patch.addProperty("content", content);
        try { table.update(uuid, vector(embedding(content)), patch); }
        catch (IOException e) { throw new IllegalStateException("Update memory failed: " + uuid, e); }
    }

    /** Preserve hidden records for audit and possible recovery. */
    public void delete(UUID uuid) {
        JsonObject patch = new JsonObject();
        patch.addProperty("confidence", -1.0);
        try { table.payload(uuid, patch); }
        catch (IOException e) { throw new IllegalStateException("Delete memory failed: " + uuid, e); }
    }

    public List<Item> getLastMemoryItems(List<String> locationIds, int limit) {
        List<String> locations = locations(locationIds);
        if (locations.isEmpty() || limit <= 0) return List.of();
        try {
            return table.list(VISIBLE + locationPredicate(locations), locations,
                    "(payload->>'create_at')::bigint DESC NULLS LAST, id", limit).stream().map(Memory::item).toList();
        } catch (IOException e) { throw new IllegalStateException("Recent memory lookup failed", e); }
    }

    public List<Item> getHighImportanceItems(List<String> locationIds, int limit, float minImportance) {
        List<String> locations = locations(locationIds);
        if (locations.isEmpty() || limit <= 0) return List.of();
        List<Object> parameters = new ArrayList<>(locations);
        parameters.add(minImportance);
        try {
            return table.list(VISIBLE + locationPredicate(locations) + " AND (payload->>'importance')::float8 >= ?",
                    parameters, "((payload->>'importance')::float8 * 0.6 + (payload->>'confidence')::float8 * 0.4) DESC, id",
                    limit).stream().map(Memory::item).toList();
        } catch (IOException e) { throw new IllegalStateException("Important memory lookup failed", e); }
    }

    private static List<String> locations(List<String> ids) {
        return ids == null ? List.of() : ids.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
    }

    private static String locationPredicate(List<String> locations) {
        return locations.isEmpty() ? "" : " AND payload->>'location_id' IN (" + String.join(",", Collections.nCopies(locations.size(), "?")) + ")";
    }

    static JsonObject payload(Item item) {
        JsonObject payload = new JsonObject();
        payload.addProperty("content", item.content);
        payload.addProperty("create_at", item.createAt);
        payload.addProperty("update_at", item.updateAt);
        payload.addProperty("location_id", item.locationId);
        payload.addProperty("confidence", item.confidence);
        payload.addProperty("importance", item.importance);
        return payload;
    }

    static Item item(PgVectorTable.Point point) {
        JsonObject payload = point.payload();
        Item item = new Item();
        item.id = point.id();
        item.score = (float) point.score();
        item.content = text(payload, "content");
        item.locationId = text(payload, "location_id");
        item.createAt = (long) number(payload, "create_at", 0);
        item.updateAt = (long) number(payload, "update_at", 0);
        item.confidence = (float) number(payload, "confidence", 0);
        item.importance = (float) number(payload, "importance", 0.5);
        return item;
    }

    private static String text(JsonObject payload, String key) {
        return !payload.has(key) || payload.get(key).isJsonNull() ? null : payload.get(key).getAsString();
    }

    private static double number(JsonObject payload, String key, double fallback) {
        return !payload.has(key) || payload.get(key).isJsonNull() ? fallback : payload.get(key).getAsDouble();
    }

    private List<Float> vector(double[] values) throws IOException {
        if (values == null) throw new IOException("Empty embedding");
        List<Float> result = new ArrayList<>();
        for (double value : values) result.add((float) value);
        PgVectorTable.vector(result, dimension);
        return result;
    }

    public double[] embedding(String text) throws IOException {
        EmbeddingRequest request = new EmbeddingRequest();
        request.dimension = dimension;
        MFChain input = new MFChain();
        input.add(new TextField(text));
        request.message.add(input);
        var response = provider.embedding(request);
        if (response == null || response.data == null || response.data.size() != 1 || response.data.get(0).embedding == null)
            throw new IOException("Empty embedding response");
        vector(response.data.get(0).embedding);
        return response.data.get(0).embedding;
    }

    public static float retrievalScore(Item item) {
        return item.score * 0.5f + item.importance * 0.3f + item.confidence * 0.2f;
    }

    public static final Comparator<Item> RETRIEVAL_ORDER = Comparator
            .comparingDouble(Memory::retrievalScore).reversed().thenComparing(item -> item.id);

    public static class Item {
        public UUID id = UUID.randomUUID();
        public String content;
        public long createAt;
        public long updateAt;
        public String locationId;
        public float score;
        public float confidence;
        public float importance = 0.5f;

        @Override
        public String toString() {
            return "|time=" + formatTimestamp(updateAt)
                    + "|confidence=" + confidence
                    + "|importance=" + importance
                    + "|loc=" + locationId + "]: "
                    + content;
        }
    }
}
