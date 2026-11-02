import com.google.gson.*;
import com.nekoyu.Universe.AIChat.PgVectorTable;
import com.nekoyu.Universe.AIChat.knowledge.KnowledgeFactTable;
import com.nekoyu.Universe.AIChat.knowledge.KnowledgeFactTable.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** One-time offline conversion; no legacy storage support is included in the application. */
public class MigrateKnowledgeFacts {
    record Original(UUID id, String embedding, JsonObject payload) {}
    record Document(String url, JsonObject row) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("migrate", "verify", "restore").contains(args[0]))
            throw new IllegalArgumentException("migrate|verify|restore BACKUP SCHEMA.FACTS SCHEMA.DOCUMENTS");
        String table = PgVectorTable.sqlName(args[2]), documentsTable = PgVectorTable.sqlName(args[3]);
        Path backup = Path.of(args[1]);
        DriverManager.setLoginTimeout(15);
        try (Connection c = DriverManager.getConnection(required("PG_URL"), required("PG_USER"), required("PG_PASSWORD"))) {
            c.setAutoCommit(false);
            try {
                try (Statement s = c.createStatement()) {
                    s.execute("SET LOCAL lock_timeout = '5s'");
                    s.execute("SET LOCAL statement_timeout = '120s'");
                    s.execute("LOCK TABLE " + table + " IN " + (args[0].equals("verify") ? "SHARE" : "ACCESS EXCLUSIVE") + " MODE");
                    s.execute("LOCK TABLE " + documentsTable + " IN SHARE MODE");
                }
                List<Original> original;
                Map<Long, Document> documents;
                if (args[0].equals("migrate")) {
                    original = readOriginal(c, table);
                    documents = readDocuments(c, documentsTable);
                    backup(backup, original, documents);
                    migrate(c, table, original, documents);
                } else {
                    original = loadOriginal(backup);
                    documents = loadDocuments(backup);
                }
                verifyDocuments(c, documentsTable, documents);
                verify(c, table, original, documents);
                if (args[0].equals("restore")) restore(c, table, original);
                if (!args[0].equals("verify")) c.commit();
                else c.rollback();
                System.out.println(args[0].toUpperCase(Locale.ROOT) + " OK: " + original.size() + " facts, "
                        + original.stream().mapToInt(r -> r.payload().getAsJsonArray("sources").size()).sum()
                        + " sources, " + documents.size() + " documents unchanged; UUIDs and all vector components verified.");
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    static List<Original> readOriginal(Connection c, String table) throws SQLException {
        List<Original> rows = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT id, embedding::text, payload FROM " + table + " ORDER BY id")) {
            while (r.next()) rows.add(new Original(r.getObject(1, UUID.class), r.getString(2), JsonParser.parseString(r.getString(3)).getAsJsonObject()));
        }
        return rows;
    }

    static Map<Long, Document> readDocuments(Connection c, String table) throws SQLException {
        Map<Long, Document> rows = new LinkedHashMap<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT id, url, to_jsonb(d)::text FROM " + table + " d ORDER BY id")) {
            while (r.next()) rows.put(r.getLong(1), new Document(r.getString(2), JsonParser.parseString(r.getString(3)).getAsJsonObject()));
        }
        return rows;
    }

    static Fact convert(Original row, Map<Long, Document> documents) {
        JsonObject p = row.payload();
        List<Source> sources = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (JsonElement item : p.getAsJsonArray("sources")) {
            JsonObject source = item.getAsJsonObject();
            long id = source.get("document_id").getAsLong();
            Document document = documents.get(id);
            if (document == null || !document.url().equals(source.get("url").getAsString()) || !seen.add(id))
                throw new IllegalStateException("Invalid or duplicate source document in fact " + row.id());
            sources.add(new Source(id, source.get("query").getAsString(), source.get("conclusion").getAsString(),
                    source.get("confidence").getAsFloat(), Instant.ofEpochMilli(source.get("ttl").getAsLong())));
        }
        if (sources.isEmpty()) throw new IllegalStateException("Fact has no sources: " + row.id());
        Instant due = sources.stream().map(Source::reviewDueAt).max(Comparator.naturalOrder()).orElseThrow();
        if (!due.equals(Instant.ofEpochMilli(p.get("ttl").getAsLong()))) throw new IllegalStateException("Inconsistent review deadline: " + row.id());
        return new Fact(row.id(), p.get("query").getAsString(), p.get("conclusion").getAsString(),
                p.get("confidence").getAsFloat(), due, Instant.ofEpochMilli(p.get("created_at").getAsLong()),
                Instant.ofEpochMilli(p.get("updated_at").getAsLong()), sources);
    }

    static void migrate(Connection c, String table, List<Original> original, Map<Long, Document> documents) throws Exception {
        List<Fact> facts = original.stream().map(r -> convert(r, documents)).toList();
        int dimension;
        try (PreparedStatement s = c.prepareStatement("SELECT atttypmod FROM pg_attribute WHERE attrelid=?::regclass AND attname='embedding'")) {
            s.setString(1, table);
            try (ResultSet r = s.executeQuery()) { if (!r.next()) throw new SQLException("Missing embedding"); dimension = r.getInt(1); }
        }
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE " + table + " ADD COLUMN question text CHECK (btrim(question) <> ''),"
                    + " ADD COLUMN conclusion text CHECK (btrim(conclusion) <> ''),"
                    + " ADD COLUMN confidence real CHECK (confidence >= 0 AND confidence <= 1),"
                    + " ADD COLUMN review_due_at timestamptz, ADD COLUMN created_at timestamptz DEFAULT now(),"
                    + " ADD COLUMN updated_at timestamptz DEFAULT now(),"
                    + " ADD COLUMN sources jsonb CHECK (jsonb_typeof(sources)='array' AND jsonb_array_length(sources)>0)");
        }
        try (PreparedStatement s = c.prepareStatement("UPDATE " + table + " SET question=?, conclusion=?, confidence=?, review_due_at=?, created_at=?, updated_at=?, sources=?::jsonb WHERE id=?")) {
            for (Fact fact : facts) {
                s.setString(1, fact.question()); s.setString(2, fact.conclusion()); s.setFloat(3, fact.confidence());
                s.setObject(4, fact.reviewDueAt().atOffset(ZoneOffset.UTC));
                s.setObject(5, fact.createdAt().atOffset(ZoneOffset.UTC)); s.setObject(6, fact.updatedAt().atOffset(ZoneOffset.UTC));
                s.setString(7, KnowledgeFactTable.sourcesJson(fact.sources())); s.setObject(8, fact.id());
                if (s.executeUpdate() != 1) throw new SQLException("Missing fact " + fact.id());
            }
        }
        try (Statement s = c.createStatement()) {
            for (String column : List.of("question", "conclusion", "confidence", "review_due_at", "created_at", "updated_at", "sources"))
                s.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " SET NOT NULL");
            // Dropping the payload also removes its obsolete GIN index; no shadow columns or triggers remain.
            s.execute("ALTER TABLE " + table + " DROP COLUMN payload");
        }
        KnowledgeFactTable.initialize(c, table, dimension);
        verify(c, table, original, documents);
    }

    static void verify(Connection c, String table, List<Original> original, Map<Long, Document> documents) throws Exception {
        Map<UUID, Original> expected = new HashMap<>();
        original.forEach(r -> expected.put(r.id(), r));
        int count = 0;
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT id, question, conclusion, confidence, review_due_at, created_at, updated_at, sources, embedding::text FROM " + table)) {
            while (r.next()) {
                Original old = expected.remove(r.getObject(1, UUID.class));
                if (old == null) throw new IllegalStateException("Unexpected fact");
                Fact actual = new Fact(old.id(), r.getString(2), r.getString(3), r.getFloat(4), r.getTimestamp(5).toInstant(),
                        r.getTimestamp(6).toInstant(), r.getTimestamp(7).toInstant(), KnowledgeFactTable.parseSources(r.getString(8)));
                if (!convert(old, documents).equals(actual) || !old.embedding().equals(r.getString(9)))
                    throw new IllegalStateException("Fact/vector mismatch: " + old.id());
                count++;
            }
        }
        if (!expected.isEmpty() || count != original.size()) throw new IllegalStateException("Fact count mismatch");
        try (PreparedStatement s = c.prepareStatement("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name=? AND column_name='payload'")) {
            s.setString(1, table.split("\\.")[0]); s.setString(2, table.split("\\.")[1]);
            try (ResultSet r = s.executeQuery()) { r.next(); if (r.getInt(1) != 0) throw new IllegalStateException("Obsolete payload column remains"); }
        }
    }

    static void verifyDocuments(Connection c, String table, Map<Long, Document> documents) throws SQLException {
        if (!documents.equals(readDocuments(c, table))) throw new IllegalStateException("Source documents changed");
    }

    static void backup(Path path, List<Original> rows, Map<Long, Document> documents) throws Exception {
        Files.createDirectory(path);
        List<String> facts = new ArrayList<>();
        for (Original row : rows) {
            JsonObject fields = new JsonObject(); fields.addProperty("id", row.id().toString());
            fields.addProperty("embedding", row.embedding()); fields.add("payload", row.payload()); facts.add(fields.toString());
        }
        write(path.resolve("facts.jsonl"), facts);
        write(path.resolve("documents.jsonl"), documents.values().stream().map(d -> d.row().toString()).toList());
        System.out.println("Backup saved: " + path.toAbsolutePath());
    }

    static void write(Path path, List<String> lines) throws Exception {
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(Path.of(path + ".sha256"), hash(path), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    static String hash(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    static List<String> read(Path path) throws Exception {
        if (!hash(path).equals(Files.readString(Path.of(path + ".sha256"), StandardCharsets.UTF_8).trim()))
            throw new IllegalStateException("Backup checksum mismatch: " + path);
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }
    static List<Original> loadOriginal(Path path) throws Exception {
        List<Original> rows = new ArrayList<>();
        for (String line : read(path.resolve("facts.jsonl"))) {
            JsonObject r = JsonParser.parseString(line).getAsJsonObject();
            rows.add(new Original(UUID.fromString(r.get("id").getAsString()), r.get("embedding").getAsString(), r.getAsJsonObject("payload")));
        }
        return rows;
    }
    static Map<Long, Document> loadDocuments(Path path) throws Exception {
        Map<Long, Document> rows = new LinkedHashMap<>();
        for (String line : read(path.resolve("documents.jsonl"))) {
            JsonObject r = JsonParser.parseString(line).getAsJsonObject();
            rows.put(r.get("id").getAsLong(), new Document(r.get("url").getAsString(), r));
        }
        return rows;
    }

    /** Refuse restoration after any fact or document has changed since migration. */
    static void restore(Connection c, String table, List<Original> original) throws Exception {
        try (Statement s = c.createStatement()) { s.execute("ALTER TABLE " + table + " ADD COLUMN payload jsonb NOT NULL DEFAULT '{}'::jsonb"); }
        try (PreparedStatement s = c.prepareStatement("UPDATE " + table + " SET payload=?::jsonb WHERE id=?")) {
            for (Original row : original) { s.setString(1, row.payload().toString()); s.setObject(2, row.id()); s.executeUpdate(); }
        }
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE " + table + " DROP COLUMN question, DROP COLUMN conclusion, DROP COLUMN confidence,"
                    + " DROP COLUMN review_due_at, DROP COLUMN created_at, DROP COLUMN updated_at, DROP COLUMN sources");
            String name = table.substring(table.indexOf('.') + 1);
            s.execute("CREATE INDEX " + name + "_payload_idx ON " + table + " USING gin (payload)");
        }
        if (!original.equals(readOriginal(c, table))) throw new IllegalStateException("Restored rows differ from backup");
    }
}
