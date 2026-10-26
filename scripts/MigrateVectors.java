import com.google.gson.*;
import com.nekoyu.Universe.AIChat.PgVectorTable;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;

/** One-time Qdrant -> PostgreSQL migration. Credentials are read only from process environment. */
public class MigrateVectors {
    private static final Map<String, String> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("moyu_universe_aic_memory", "aichat.memory");
        TABLES.put("moyu_universe_aic_ks", "aichat.knowledge_fact");
        TABLES.put("moyu_universe_aic_ekb", "aichat.legacy_knowledge");
        TABLES.put("moyu_universe_aic_ks_research", "aichat.research_task");
    }
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final String RESEARCH = "moyu_universe_aic_ks_research";
    private static final int DIMENSION = 1024;

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("copy", "verify", "delete", "restore").contains(args[0]))
            throw new IllegalArgumentException("Usage: copy <new-backup-directory> | verify|delete|restore <backup-directory>");
        String mode = args[0];
        Path backup = Path.of(args[1]);
        Map<String, JsonObject> snapshots = new LinkedHashMap<>();
        if (mode.equals("copy")) {
            Files.createDirectory(backup);
            for (String name : TABLES.keySet()) {
                JsonObject snapshot = snapshot(name);
                snapshots.put(name, snapshot);
                write(backup.resolve(name + ".json"), snapshot);
                System.out.println("BACKUP " + name + " points=" + snapshot.getAsJsonArray("points").size());
            }
            try (Connection c = connect()) {
                write(backup.resolve("postgres-before.json"), archive(c));
            }
        } else {
            for (String name : TABLES.keySet()) snapshots.put(name, read(backup.resolve(name + ".json")));
        }

        try (Connection c = connect()) {
            if (mode.equals("copy") || mode.equals("restore")) {
                c.setAutoCommit(false);
                try {
                    try (Statement s = c.createStatement()) {
                        s.execute("SET LOCAL lock_timeout='5s'");
                        s.execute("SET LOCAL statement_timeout='120s'");
                    }
                    for (var entry : TABLES.entrySet()) {
                        if (!entry.getKey().equals(RESEARCH)) PgVectorTable.initialize(c, entry.getValue(), DIMENSION);
                    }
                    try (Statement s = c.createStatement()) {
                        s.execute("ALTER TABLE aichat.research_task ADD COLUMN IF NOT EXISTS embedding vector(1024)");
                        s.execute("CREATE INDEX IF NOT EXISTS research_task_embedding_idx ON aichat.research_task USING hnsw (embedding vector_cosine_ops)");
                    }
                    for (var entry : snapshots.entrySet()) importPoints(c, entry.getKey(), entry.getValue());
                    verifyAll(c, snapshots);
                    verifyArchive(c, read(backup.resolve("postgres-before.json")));
                    c.commit();
                    System.out.println("COMMITTED vector migration");
                } catch (Exception error) { c.rollback(); throw error; }
            } else {
                c.setReadOnly(true);
                c.setAutoCommit(false);
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                verifyAll(c, snapshots);
                verifyArchive(c, read(backup.resolve("postgres-before.json")));
                c.rollback();
            }
        }

        if (mode.equals("verify") || mode.equals("delete")) {
            // Re-read every source, not just counts: refuse cleanup if anything changed since backup.
            for (var entry : snapshots.entrySet()) {
                JsonObject live = snapshot(entry.getKey());
                if (!pointMap(live).equals(pointMap(entry.getValue())))
                    throw new IllegalStateException("Source changed after backup: " + entry.getKey());
                System.out.println("SOURCE UNCHANGED " + entry.getKey());
            }
        }
        if (mode.equals("delete")) {
            // All four target datasets and unchanged sources have been verified before the first DELETE.
            for (String name : TABLES.keySet()) {
                request("DELETE", "/collections/" + name, null);
                System.out.println("DELETED " + name);
            }
            JsonArray collections = request("GET", "/collections", null).getAsJsonObject("result").getAsJsonArray("collections");
            for (JsonElement item : collections) {
                if (TABLES.containsKey(item.getAsJsonObject().get("name").getAsString()))
                    throw new IllegalStateException("Collection still exists after deletion");
            }
            System.out.println("VERIFIED all four migrated collections absent");
        }
    }

    private static JsonObject snapshot(String name) throws Exception {
        JsonObject info = request("GET", "/collections/" + name, null).getAsJsonObject("result");
        JsonObject vectors = info.getAsJsonObject("config").getAsJsonObject("params").getAsJsonObject("vectors");
        if (vectors.size() != 1) throw new IllegalStateException("Expected exactly one named vector in " + name);
        String vectorName = vectors.keySet().iterator().next();
        JsonObject settings = vectors.getAsJsonObject(vectorName);
        if (settings.get("size").getAsInt() != DIMENSION || !settings.get("distance").getAsString().equals("Cosine"))
            throw new IllegalStateException("Unexpected vector configuration: " + name);
        JsonArray points = new JsonArray();
        JsonElement offset = JsonNull.INSTANCE;
        do {
            JsonObject body = new JsonObject();
            body.addProperty("limit", 128);
            body.addProperty("with_payload", true);
            body.addProperty("with_vector", true);
            if (!offset.isJsonNull()) body.add("offset", offset);
            JsonObject page = request("POST", "/collections/" + name + "/points/scroll", body).getAsJsonObject("result");
            points.addAll(page.getAsJsonArray("points"));
            offset = page.has("next_page_offset") ? page.get("next_page_offset") : JsonNull.INSTANCE;
        } while (!offset.isJsonNull());
        JsonObject countBody = new JsonObject();
        countBody.addProperty("exact", true);
        long exact = request("POST", "/collections/" + name + "/points/count", countBody).getAsJsonObject("result").get("count").getAsLong();
        if (exact != points.size()) throw new IllegalStateException("Unstable source count: " + name);
        JsonObject result = new JsonObject();
        result.addProperty("collection", name);
        result.addProperty("vector_name", vectorName);
        result.add("info", info);
        result.add("points", points);
        pointMap(result); // Validate IDs and duplicate-free pagination before saving.
        return result;
    }

    private static Map<UUID, JsonObject> pointMap(JsonObject snapshot) {
        Map<UUID, JsonObject> points = new TreeMap<>();
        for (JsonElement item : snapshot.getAsJsonArray("points")) {
            JsonObject point = item.getAsJsonObject();
            UUID id = UUID.fromString(point.get("id").getAsString());
            if (points.put(id, point) != null) throw new IllegalStateException("Duplicate point " + id);
        }
        return points;
    }

    private static String vector(JsonObject point, JsonObject snapshot) throws Exception {
        JsonElement vectors = point.get("vector");
        JsonArray values = vectors.isJsonArray() ? vectors.getAsJsonArray()
                : vectors.getAsJsonObject().getAsJsonArray(snapshot.get("vector_name").getAsString());
        List<Float> numbers = new ArrayList<>();
        for (JsonElement value : values) numbers.add(value.getAsFloat());
        return PgVectorTable.vector(numbers, DIMENSION);
    }

    private static void importPoints(Connection c, String name, JsonObject snapshot) throws Exception {
        String table = TABLES.get(name);
        String sql = name.equals(RESEARCH)
                ? "UPDATE " + table + " SET embedding=?::vector WHERE id=? AND embedding IS NULL"
                : "INSERT INTO " + table + " (embedding, id, payload) VALUES (?::vector, ?, ?::jsonb) ON CONFLICT (id) DO NOTHING";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (var entry : pointMap(snapshot).entrySet()) {
                JsonObject point = entry.getValue();
                s.setString(1, vector(point, snapshot));
                s.setObject(2, entry.getKey());
                JsonObject payload = point.has("payload") ? point.getAsJsonObject("payload") : new JsonObject();
                if (name.equals(RESEARCH)) {
                    if (!payload.isEmpty()) throw new IllegalStateException("Unexpected research payload; refusing lossy migration");
                } else s.setString(3, payload.toString());
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    private static void verifyAll(Connection c, Map<String, JsonObject> snapshots) throws Exception {
        for (var entry : snapshots.entrySet()) {
            String name = entry.getKey();
            JsonObject snapshot = entry.getValue();
            Map<UUID, JsonObject> expected = pointMap(snapshot);
            int count = 0;
            String sql = name.equals(RESEARCH)
                    ? "SELECT id, embedding::text FROM aichat.research_task WHERE embedding IS NOT NULL ORDER BY id"
                    : "SELECT id, embedding::text, payload::text FROM " + TABLES.get(name) + " ORDER BY id";
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
                while (r.next()) {
                    UUID id = r.getObject(1, UUID.class);
                    JsonObject source = expected.get(id);
                    if (source == null) throw new IllegalStateException("Unexpected target row in " + name + ": " + id);
                    JsonArray actual = JsonParser.parseString(r.getString(2)).getAsJsonArray();
                    JsonArray original = JsonParser.parseString(vector(source, snapshot)).getAsJsonArray();
                    if (actual.size() != DIMENSION) throw new IllegalStateException("Wrong target dimension");
                    for (int i = 0; i < DIMENSION; i++) {
                        if (Float.floatToIntBits(actual.get(i).getAsFloat()) != Float.floatToIntBits(original.get(i).getAsFloat()))
                            throw new IllegalStateException("Vector differs in " + name + " id=" + id + " dimension=" + i);
                    }
                    if (!name.equals(RESEARCH)) {
                        JsonElement payload = source.has("payload") ? source.get("payload") : new JsonObject();
                        if (!JsonParser.parseString(r.getString(3)).equals(payload))
                            throw new IllegalStateException("Payload differs in " + name + " id=" + id);
                    }
                    count++;
                }
            }
            if (count != expected.size()) throw new IllegalStateException("Count mismatch: " + name);
            System.out.println("VERIFIED " + name + " -> " + TABLES.get(name) + " rows=" + count + " (all IDs, float32 vector bits, JSON payloads)");
        }
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("""
                SELECT count(*) FROM aichat.knowledge_fact f
                WHERE NOT EXISTS (SELECT 1 FROM aichat.knowledge_document d WHERE d.id=(f.payload->>'document_id')::bigint)
                   OR EXISTS (SELECT 1 FROM jsonb_array_elements(COALESCE(f.payload->'sources', '[]'::jsonb)) x
                              WHERE NOT EXISTS (SELECT 1 FROM aichat.knowledge_document d WHERE d.id=(x->>'document_id')::bigint))
                """)) {
            r.next();
            if (r.getLong(1) != 0) throw new IllegalStateException("Unresolved knowledge document references: " + r.getLong(1));
            System.out.println("VERIFIED all knowledge document references");
        }
    }

    private static JsonObject archive(Connection c) throws SQLException {
        JsonObject result = new JsonObject();
        for (String table : List.of("knowledge_document", "research_task")) {
            JsonArray rows = new JsonArray();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT to_jsonb(t) - 'embedding' FROM aichat." + table + " t ORDER BY id")) {
                while (r.next()) rows.add(JsonParser.parseString(r.getString(1)));
            }
            result.add(table, rows);
        }
        return result;
    }

    private static void verifyArchive(Connection c, JsonObject before) throws SQLException {
        if (!before.equals(archive(c))) throw new IllegalStateException("Existing PostgreSQL documents/reports changed since backup");
        System.out.println("VERIFIED existing PostgreSQL documents and reports unchanged");
    }

    private static Connection connect() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", required("PG_USER"));
        p.setProperty("password", required("PG_PASSWORD"));
        p.setProperty("connectTimeout", "10");
        p.setProperty("socketTimeout", "120");
        p.setProperty("ApplicationName", "MoyuUniverse vector migration");
        return DriverManager.getConnection(required("PG_URL"), p);
    }

    private static JsonObject request(String method, String path, JsonObject body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(required("QDRANT_URL") + path))
                .timeout(Duration.ofSeconds(45)).header("api-key", required("QDRANT_API_KEY"));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("Qdrant " + method + " " + path + " HTTP " + response.statusCode());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static void write(Path path, JsonObject value) throws Exception {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
        Files.writeString(Path.of(path + ".sha256"), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), StandardOpenOption.CREATE_NEW);
    }

    private static JsonObject read(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        String expected = Files.readString(Path.of(path + ".sha256")).strip();
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!actual.equals(expected)) throw new IllegalStateException("Backup checksum mismatch: " + path);
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
}
