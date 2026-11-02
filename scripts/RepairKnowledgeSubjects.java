import com.google.gson.*;
import com.nekoyu.Universe.AIChat.PgVectorTable;
import com.nekoyu.Universe.AIChat.knowledge.KnowledgeFactTable;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Exact-preimage guarded repair of reviewed question/conclusion text; credentials stay in the environment. */
public class RepairKnowledgeSubjects {
    private static final String FACTS = "aichat.knowledge_fact", DOCUMENTS = "aichat.knowledge_document";
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("rehearse", "apply", "verify").contains(args[0]))
            throw new IllegalArgumentException("rehearse|apply|verify PLAN.json VECTORS.json|- BACKUP_DIRECTORY");
        JsonArray plan = JsonParser.parseString(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8)).getAsJsonArray();
        JsonObject vectors = args[2].equals("-") ? new JsonObject()
                : JsonParser.parseString(Files.readString(Path.of(args[2]), StandardCharsets.UTF_8)).getAsJsonObject();
        Path backup = Path.of(args[3]);
        DriverManager.setLoginTimeout(15);
        try (Connection c = DriverManager.getConnection(required("PG_URL"), required("PG_USER"), required("PG_PASSWORD"))) {
            c.setAutoCommit(false);
            try {
                try (Statement s = c.createStatement()) {
                    s.execute("SET LOCAL lock_timeout='5s'");
                    s.execute("SET LOCAL statement_timeout='120s'");
                    s.execute("LOCK TABLE " + FACTS + " IN " + (args[0].equals("verify") ? "SHARE" : "SHARE ROW EXCLUSIVE") + " MODE");
                    s.execute("LOCK TABLE " + DOCUMENTS + " IN SHARE MODE");
                }
                if (args[0].equals("verify")) {
                    verifySnapshot(c, backup, "after.jsonl", FACTS);
                    verifySnapshot(c, backup, "documents.jsonl", DOCUMENTS);
                    c.rollback();
                    System.out.println("VERIFY OK: all fact fields, vectors and documents match the committed repair snapshot.");
                    return;
                }
                Map<String, JsonObject> before = rows(c, FACTS);
                Map<String, JsonObject> documents = rows(c, DOCUMENTS);
                Map<String, JsonObject> expected = new LinkedHashMap<>();
                before.forEach((id, row) -> expected.put(id, row.deepCopy()));
                Set<String> seen = new HashSet<>();
                int reembedded = 0;
                Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                for (JsonElement item : plan) {
                    JsonObject patch = item.getAsJsonObject();
                    String id = patch.get("fact_id").getAsString();
                    if (!seen.add(id)) throw new IllegalStateException("Duplicate plan UUID: " + id);
                    JsonObject actual = before.get(id);
                    if (actual == null) throw new IllegalStateException("Missing fact: " + id);
                    JsonObject preimage = actual.deepCopy(); preimage.remove("embedding");
                    if (!normalize(preimage).equals(normalize(patch.getAsJsonObject("expected_before"))))
                        throw new IllegalStateException("Fact changed since review: " + id);
                    JsonObject after = patch.getAsJsonObject("after");
                    if (!after.keySet().equals(Set.of("question", "conclusion", "sources")))
                        throw new IllegalStateException("Repair may change only question, conclusion, sources: " + id);
                    validateText(after.get("question"), 300); validateText(after.get("conclusion"), 1000);
                    JsonArray oldSources = actual.getAsJsonArray("sources"), newSources = after.getAsJsonArray("sources");
                    if (oldSources.size() != newSources.size()) throw new IllegalStateException("Repair cannot add/remove sources: " + id);
                    for (int i = 0; i < oldSources.size(); i++) {
                        JsonObject old = oldSources.get(i).getAsJsonObject(), revised = newSources.get(i).getAsJsonObject();
                        if (!old.keySet().equals(revised.keySet())) throw new IllegalStateException("Source shape changed: " + id);
                        validateText(revised.get("question"), 300); validateText(revised.get("conclusion"), 1000);
                        JsonObject fixedOld = old.deepCopy(), fixedNew = revised.deepCopy();
                        fixedOld.remove("question"); fixedOld.remove("conclusion"); fixedNew.remove("question"); fixedNew.remove("conclusion");
                        if (!normalize(fixedOld).equals(normalize(fixedNew))) throw new IllegalStateException("Source identity, confidence or deadline changed: " + id);
                        if (!documents.containsKey(revised.get("document_id").getAsString())) throw new IllegalStateException("Missing source document: " + id);
                    }
                    KnowledgeFactTable.parseSources(newSources.toString());
                    boolean changedQuestion = !actual.get("question").equals(after.get("question"));
                    if (patch.get("embedding_needs_refresh").getAsBoolean() != changedQuestion)
                        throw new IllegalStateException("Embedding flag mismatch: " + id);
                    if (!changedQuestion && actual.get("conclusion").equals(after.get("conclusion"))
                            && normalize(actual).get("sources").equals(normalize(after).get("sources")))
                        throw new IllegalStateException("No-op repair: " + id);
                    JsonObject target = expected.get(id);
                    for (String key : after.keySet()) target.add(key, after.get(key).deepCopy());
                    target.addProperty("updated_at", now.toString());
                    if (changedQuestion) {
                        JsonObject embedded = vectors.getAsJsonObject(id);
                        if (embedded == null || !hash(after.get("question").getAsString().getBytes(StandardCharsets.UTF_8))
                                .equals(embedded.get("input_sha256").getAsString()))
                            throw new IllegalStateException("Missing or stale question embedding: " + id);
                        if (!required("EMBED_MODEL").equals(embedded.get("model").getAsString()))
                            throw new IllegalStateException("Embedding model mismatch: " + id);
                        List<Float> values = new ArrayList<>();
                        for (JsonElement value : embedded.getAsJsonArray("embedding")) values.add(value.getAsFloat());
                        target.addProperty("embedding", PgVectorTable.vector(values, 1024));
                        reembedded++;
                    }
                }
                Files.createDirectory(backup);
                writeSnapshot(backup.resolve("before.jsonl"), before);
                writeSnapshot(backup.resolve("documents.jsonl"), documents);
                Files.copy(Path.of(args[1]), backup.resolve("plan.json"));
                for (String id : seen) {
                    JsonObject row = expected.get(id), old = before.get(id);
                    boolean changedQuestion = !row.get("question").equals(old.get("question"));
                    try (PreparedStatement s = c.prepareStatement("UPDATE " + FACTS
                            + " SET question=?, conclusion=?, sources=?::jsonb, updated_at=?"
                            + (changedQuestion ? ", embedding=?::vector" : "") + " WHERE id=?")) {
                        s.setString(1, row.get("question").getAsString()); s.setString(2, row.get("conclusion").getAsString());
                        s.setString(3, row.get("sources").toString()); s.setObject(4, now.atOffset(ZoneOffset.UTC));
                        int index = 5;
                        if (changedQuestion) s.setString(index++, row.get("embedding").getAsString());
                        s.setObject(index, UUID.fromString(id));
                        if (s.executeUpdate() != 1) throw new SQLException("Fact update failed: " + id);
                    }
                }
                Map<String, JsonObject> written = rows(c, FACTS);
                // pgvector formats float32 values canonically; compare every component by float32 bits.
                if (!equivalent(expected, written)) throw new IllegalStateException("Post-write fact/vector verification failed");
                if (!equivalent(documents, rows(c, DOCUMENTS))) throw new IllegalStateException("Documents changed");
                writeSnapshot(backup.resolve("after.jsonl"), written);
                if (args[0].equals("apply")) c.commit(); else c.rollback();
                String result = (args[0].equals("apply") ? "COMMITTED" : "REHEARSAL ROLLED BACK") + ": facts=" + seen.size()
                        + ", refreshed_question_vectors=" + reembedded + ", all other fact fields/vectors and documents unchanged.";
                Files.writeString(backup.resolve("result.txt"), result, StandardCharsets.UTF_8);
                System.out.println(result);
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }

    static void validateText(JsonElement value, int maximum) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank() || value.getAsString().length() > maximum)
            throw new IllegalArgumentException("Invalid repaired text or length");
    }
    static JsonObject normalize(JsonObject original) {
        JsonObject result = original.deepCopy();
        for (String key : List.of("created_at", "updated_at", "review_due_at"))
            if (result.has(key)) result.addProperty(key, OffsetDateTime.parse(result.get(key).getAsString()).toInstant().toString());
        if (result.has("sources")) {
            JsonArray sources = new JsonArray();
            for (JsonElement source : result.getAsJsonArray("sources")) sources.add(normalize(source.getAsJsonObject()));
            result.add("sources", sources);
        }
        return result;
    }
    static Map<String, JsonObject> rows(Connection c, String table) throws SQLException {
        Map<String, JsonObject> rows = new LinkedHashMap<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY id")) {
            while (r.next()) { JsonObject row = JsonParser.parseString(r.getString(1)).getAsJsonObject(); rows.put(row.get("id").getAsString(), row); }
        }
        return rows;
    }
    static boolean equivalent(Map<String, JsonObject> expected, Map<String, JsonObject> actual) {
        if (!expected.keySet().equals(actual.keySet())) return false;
        for (String id : expected.keySet()) {
            JsonObject a = normalize(expected.get(id)), b = normalize(actual.get(id));
            if (a.has("embedding")) {
                JsonArray va = JsonParser.parseString(a.remove("embedding").getAsString()).getAsJsonArray();
                JsonArray vb = JsonParser.parseString(b.remove("embedding").getAsString()).getAsJsonArray();
                if (va.size() != vb.size()) return false;
                for (int i = 0; i < va.size(); i++) if (Float.floatToIntBits(va.get(i).getAsFloat()) != Float.floatToIntBits(vb.get(i).getAsFloat())) return false;
            }
            if (!a.equals(b)) return false;
        }
        return true;
    }
    static void writeSnapshot(Path file, Map<String, JsonObject> rows) throws Exception {
        Files.write(file, rows.values().stream().map(JSON::toJson).toList(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(Path.of(file + ".sha256"), hash(Files.readAllBytes(file)), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    static void verifySnapshot(Connection c, Path backup, String file, String table) throws Exception {
        Path path = backup.resolve(file);
        if (!hash(Files.readAllBytes(path)).equals(Files.readString(Path.of(path + ".sha256")).trim()))
            throw new IllegalStateException("Backup hash mismatch: " + file);
        Map<String, JsonObject> expected = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            JsonObject row = JsonParser.parseString(line).getAsJsonObject(); expected.put(row.get("id").getAsString(), row);
        }
        if (!equivalent(expected, rows(c, table))) throw new IllegalStateException("Committed snapshot changed: " + file);
    }
    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static String required(String name) { return Objects.requireNonNull(System.getenv(name), "Missing " + name); }
}
