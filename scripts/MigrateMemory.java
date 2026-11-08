import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Offline conversion of the PostgreSQL memory payload to typed columns. */
public class MigrateMemory {
    private static final String KEYS = "ARRAY['content','location_id','create_at','update_at','confidence','importance','score']";
    private static final String LEGACY = """
            SELECT id, embedding::text, payload->>'content', payload->>'location_id',
                   (payload->>'create_at')::bigint, (payload->>'update_at')::bigint,
                   (payload->>'confidence')::float8, (payload->>'importance')::float8
            FROM %s ORDER BY id
            """;
    private static final String TYPED = """
            SELECT id, embedding::text, content, location_id, create_at, update_at, confidence, importance
            FROM %s ORDER BY id
            """;

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !Set.of("inspect", "rehearse", "migrate", "verify").contains(args[0])
                || !args[2].matches("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*"))
            throw new IllegalArgumentException("inspect|rehearse|migrate|verify BACKUP SCHEMA.MEMORY");
        String mode = args[0], table = args[2];
        Path backup = Path.of(args[1]);
        DriverManager.setLoginTimeout(10);
        try (Connection c = DriverManager.getConnection(System.getenv("PG_URL"), System.getenv("PG_USER"), System.getenv("PG_PASSWORD"))) {
            boolean readOnly = mode.equals("verify") || mode.equals("inspect");
            c.setReadOnly(readOnly);
            c.setTransactionIsolation(readOnly ? Connection.TRANSACTION_REPEATABLE_READ : Connection.TRANSACTION_SERIALIZABLE);
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL lock_timeout = '5s'");
                s.execute("SET LOCAL statement_timeout = '120s'");
                if (mode.equals("inspect")) {
                    try (ResultSet r = s.executeQuery("SELECT count(*), count(*) FILTER (WHERE (payload->>'confidence')::float8 < 0), "
                            + "count(*) FILTER (WHERE jsonb_typeof(payload) <> 'object' OR payload - " + KEYS + " <> '{}'::jsonb) FROM " + table)) {
                        r.next();
                        System.out.printf("Rows=%d hidden=%d unexpected payloads=%d%n", r.getInt(1), r.getInt(2), r.getInt(3));
                    }
                    try (ResultSet r = s.executeQuery("SELECT key, jsonb_typeof(value), count(*) FROM " + table
                            + " CROSS JOIN LATERAL jsonb_each(payload) GROUP BY 1, 2 ORDER BY 1, 2")) {
                        while (r.next()) System.out.printf("%s %s %d%n", r.getString(1), r.getString(2), r.getInt(3));
                    }
                } else if (mode.equals("verify")) {
                    requireMatch(Files.readString(backup.resolve("projection.sha256")).strip(), digest(rows(s, TYPED.formatted(table))));
                    try (ResultSet r = s.executeQuery("SELECT 1 FROM pg_attribute WHERE attrelid = '" + table
                            + "'::regclass AND attname = 'payload' AND NOT attisdropped")) {
                        if (r.next()) throw new IllegalStateException("Legacy payload still present");
                    }
                } else {
                    s.execute("LOCK TABLE " + table + " IN ACCESS EXCLUSIVE MODE");
                    try (ResultSet r = s.executeQuery("SELECT count(*) FROM " + table + " WHERE jsonb_typeof(payload) <> 'object'"
                            + " OR payload - " + KEYS + " <> '{}'::jsonb")) {
                        r.next();
                        if (r.getLong(1) != 0) throw new IllegalStateException("Unexpected memory attributes; refusing to drop payload");
                    }
                    List<List<String>> expected = rows(s, LEGACY.formatted(table));
                    if (mode.equals("migrate")) {
                        Files.createDirectory(backup);
                        StringBuilder snapshot = new StringBuilder();
                        try (ResultSet r = s.executeQuery("SELECT jsonb_build_object('id', id, 'embedding', embedding::text,"
                                + " 'payload', payload)::text FROM " + table + " ORDER BY id")) {
                            while (r.next()) snapshot.append(r.getString(1)).append('\n');
                        }
                        Files.writeString(backup.resolve("memory.jsonl"), snapshot, StandardOpenOption.CREATE_NEW);
                        Files.writeString(backup.resolve("memory.sha256"), sha256(snapshot.toString()) + "\n", StandardOpenOption.CREATE_NEW);
                        Files.writeString(backup.resolve("projection.sha256"), digest(expected) + "\n", StandardOpenOption.CREATE_NEW);
                    }
                    migrate(s, table);
                    if (!expected.equals(rows(s, TYPED.formatted(table))))
                        throw new IllegalStateException("Typed rows differ from original projection");
                    System.out.printf("Verified %d rows, including UUIDs and all vector components.%n", expected.size());
                }
                if (mode.equals("migrate")) c.commit();
                else c.rollback();
                System.out.println(mode.toUpperCase(Locale.ROOT) + " OK");
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static void migrate(Statement s, String table) throws SQLException {
        s.execute("ALTER TABLE " + table + " ADD COLUMN content text, ADD COLUMN location_id text,"
                + " ADD COLUMN create_at bigint, ADD COLUMN update_at bigint,"
                + " ADD COLUMN confidence double precision, ADD COLUMN importance double precision");
        s.execute("UPDATE " + table + " SET content=payload->>'content', location_id=payload->>'location_id',"
                + " create_at=(payload->>'create_at')::bigint, update_at=(payload->>'update_at')::bigint,"
                + " confidence=(payload->>'confidence')::float8, importance=(payload->>'importance')::float8");
        s.execute("ALTER TABLE " + table + " DROP COLUMN payload");
        s.execute("CREATE INDEX " + table.substring(table.indexOf('.') + 1) + "_location_created_idx ON " + table
                + " (location_id, create_at DESC NULLS LAST, id) WHERE confidence >= 0");
        s.execute("ANALYZE " + table);
    }

    private static List<List<String>> rows(Statement s, String sql) throws SQLException {
        List<List<String>> result = new ArrayList<>();
        try (ResultSet r = s.executeQuery(sql)) {
            while (r.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.add(r.getString(i));
                result.add(row);
            }
        }
        return result;
    }

    private static String digest(List<List<String>> rows) throws Exception {
        StringBuilder canonical = new StringBuilder();
        for (List<String> row : rows)
            for (String value : row) canonical.append(value == null ? "-1:" : value.length() + ":" + value);
        return sha256(canonical.toString());
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static void requireMatch(String expected, String actual) {
        if (!expected.equals(actual)) throw new IllegalStateException("Typed rows differ from original projection");
    }
}
