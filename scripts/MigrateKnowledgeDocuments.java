import com.nekoyu.Universe.AIChat.knowledge.DocumentArchive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Standalone JDBC migration. Credentials come exclusively from environment variables. */
public class MigrateKnowledgeDocuments {
    record Row(long id, String url, String hash, String title, String content, long created, long updated) {}
    static final String COLUMNS = "id, url, url_hash, title, content, created_at, updated_at";

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || !Set.of("copy", "verify", "restore", "sync").contains(args[0])
                || (args[0].equals("verify") ? args.length != 1 : args[0].equals("sync") ? args.length != 3 : args.length != 2)) {
            throw new IllegalArgumentException("Usage: copy <new-backup-file> | verify | restore <backup-file> | sync <baseline-backup> <new-backup>");
        }
        List<Row> baseline = args[0].equals("sync") ? checkedBackup(Path.of(args[1])) : null;
        String source = DocumentArchive.sqlName(System.getenv().getOrDefault("MYSQL_DOCUMENTS_TABLE", "ks_documents"));
        String target = DocumentArchive.sqlName(System.getenv().getOrDefault("PG_DOCUMENTS_TABLE", DocumentArchive.DEFAULT_TABLE));
        List<Row> rows;
        if (args[0].equals("restore")) {
            rows = checkedBackup(Path.of(args[1]));
        } else {
            try (Connection mysql = connect("MYSQL")) {
                mysql.setReadOnly(true);
                mysql.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                mysql.setAutoCommit(false);
                rows = readRows(mysql, source, false);
                mysql.rollback();
            }
        }
        if (args[0].equals("copy")) writeBackup(Path.of(args[1]), rows);
        if (baseline != null) {
            Set<Long> sourceIds = new HashSet<>();
            for (Row row : rows) sourceIds.add(row.id());
            if (baseline.stream().anyMatch(row -> !sourceIds.contains(row.id()))) {
                throw new IllegalStateException("Source removed baseline rows; refusing automatic deletion");
            }
            writeBackup(Path.of(args[2]), rows);
        }
        try (Connection pg = connect("PG")) {
            if (args[0].equals("verify")) {
                pg.setReadOnly(true);
                pg.setAutoCommit(false);
                verify(rows, readRows(pg, target, true));
                pg.rollback();
            } else {
                pg.setAutoCommit(false);
                try {
                    try (Statement s = pg.createStatement()) {
                        s.execute("SET LOCAL lock_timeout = '5s'");
                        s.execute("SET LOCAL statement_timeout = '120s'");
                    }
                    DocumentArchive.initialize(pg, target);
                    try (Statement s = pg.createStatement()) {
                        s.execute("LOCK TABLE " + target + " IN ACCESS EXCLUSIVE MODE");
                        if (baseline != null) {
                            // Final sync is safe only while PostgreSQL still equals the original import.
                            verify(baseline, readRows(pg, target, true));
                        } else {
                            try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + target)) {
                                r.next();
                                if (r.getLong(1) != 0) throw new IllegalStateException("Target is not empty; refusing to overwrite. Run verify instead.");
                            }
                        }
                    }
                    String conflict = baseline == null ? "" : " ON CONFLICT (id) DO UPDATE SET url=EXCLUDED.url, url_hash=EXCLUDED.url_hash, title=EXCLUDED.title, content=EXCLUDED.content, created_at=EXCLUDED.created_at, updated_at=EXCLUDED.updated_at";
                    try (PreparedStatement s = pg.prepareStatement("INSERT INTO " + target + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)" + conflict)) {
                        int batch = 0;
                        for (Row row : rows) {
                            s.setLong(1, row.id());
                            s.setString(2, row.url());
                            s.setString(3, row.hash());
                            s.setString(4, row.title());
                            s.setString(5, row.content());
                            s.setObject(6, Instant.ofEpochMilli(row.created()).atOffset(ZoneOffset.UTC));
                            s.setObject(7, Instant.ofEpochMilli(row.updated()).atOffset(ZoneOffset.UTC));
                            s.addBatch();
                            if (++batch % 100 == 0) s.executeBatch();
                        }
                        if (batch % 100 != 0) s.executeBatch();
                    }
                    verify(rows, readRows(pg, target, true));
                    // Explicit ids do not advance PostgreSQL identity sequences.
                    try (PreparedStatement s = pg.prepareStatement("SELECT setval(pg_get_serial_sequence(?, 'id'), COALESCE((SELECT MAX(id) FROM " + target + "), 0) + 1, false)")) {
                        s.setString(1, target);
                        s.execute();
                    }
                    pg.commit();
                    System.out.println("COMMITTED " + rows.size() + " documents to " + target);
                } catch (Exception e) {
                    pg.rollback();
                    throw e;
                }
            }
        }
    }

    static Connection connect(String prefix) throws SQLException {
        DriverManager.setLoginTimeout(15);
        return DriverManager.getConnection(required(prefix + "_URL"), required(prefix + "_USER"), required(prefix + "_PASSWORD"));
    }

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing environment variable " + name);
        return value;
    }

    static List<Row> readRows(Connection c, String table, boolean pg) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT " + COLUMNS + " FROM " + table + " ORDER BY id")) {
            while (r.next()) {
                long created = pg ? r.getObject(6, OffsetDateTime.class).toInstant().toEpochMilli() : r.getLong(6);
                long updated = pg ? r.getObject(7, OffsetDateTime.class).toInstant().toEpochMilli() : r.getLong(7);
                rows.add(new Row(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), created, updated));
            }
        }
        return rows;
    }

    static void verify(List<Row> source, List<Row> target) {
        if (source.size() != target.size()) throw new IllegalStateException("Count differs: source=" + source.size() + " target=" + target.size());
        for (int i = 0; i < source.size(); i++) {
            if (!source.get(i).equals(target.get(i))) {
                throw new IllegalStateException("Document differs at source id=" + source.get(i).id() + ", target id=" + target.get(i).id());
            }
        }
        System.out.println("VERIFIED " + source.size() + " rows: id, URL, hash, title, content, NULLs and epoch milliseconds match exactly");
    }

    // Versioned, length-prefixed UTF-8 snapshot, preserving nulls and arbitrarily long document bodies.
    static void writeBackup(Path file, List<Row> rows) throws Exception {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)))) {
            out.writeUTF("MOYU_KNOWLEDGE_V1");
            out.writeInt(rows.size());
            for (Row row : rows) {
                out.writeLong(row.id());
                for (String value : new String[]{row.url(), row.hash(), row.title(), row.content()}) {
                    if (value == null) out.writeInt(-1);
                    else { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes); }
                }
                out.writeLong(row.created());
                out.writeLong(row.updated());
            }
        }
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        Files.writeString(Path.of(file + ".sha256"), digest + "  " + file.getFileName() + "\n", StandardOpenOption.CREATE_NEW);
        verify(rows, readBackup(file));
        System.out.println("BACKUP " + file.toAbsolutePath() + " sha256=" + digest);
    }

    static List<Row> readBackup(Path file) throws IOException {
        List<Row> rows = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (!in.readUTF().equals("MOYU_KNOWLEDGE_V1")) throw new IOException("Unsupported backup format");
            int count = in.readInt();
            if (count < 0) throw new IOException("Invalid row count");
            for (int i = 0; i < count; i++) {
                long id = in.readLong();
                String[] fields = new String[4];
                for (int j = 0; j < fields.length; j++) {
                    int length = in.readInt();
                    if (length < -1) throw new IOException("Invalid field length");
                    if (length >= 0) {
                        byte[] bytes = in.readNBytes(length);
                        if (bytes.length != length) throw new EOFException();
                        fields[j] = new String(bytes, StandardCharsets.UTF_8);
                    }
                }
                rows.add(new Row(id, fields[0], fields[1], fields[2], fields[3], in.readLong(), in.readLong()));
            }
            if (in.read() != -1) throw new IOException("Unexpected trailing backup data");
        }
        return rows;
    }

    static List<Row> checkedBackup(Path file) throws Exception {
        String expected = Files.readString(Path.of(file + ".sha256")).trim().split("\\s+")[0];
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        if (!actual.equalsIgnoreCase(expected)) throw new IOException("Backup SHA-256 mismatch");
        return readBackup(file);
    }
}
