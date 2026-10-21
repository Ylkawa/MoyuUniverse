package com.nekoyu.Universe.AIChat.knowledge;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL tests, opt in with PG_URL/PG_USER/PG_PASSWORD; no models or Qdrant required. */
class DocumentArchivePostgresTest {
    HikariDataSource dataSource;
    DocumentArchive archive;
    String schema;
    String table;

    @BeforeEach void prepare() throws Exception {
        Assumptions.assumeTrue(System.getenv("PG_URL") != null, "PG_URL is required for live PostgreSQL tests");
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("PG_URL"));
        config.setUsername(System.getenv("PG_USER"));
        config.setPassword(System.getenv("PG_PASSWORD"));
        config.setMaximumPoolSize(2);
        config.setConnectionInitSql("SET TIME ZONE 'Asia/Shanghai'");
        dataSource = new HikariDataSource(config);
        schema = "archive_test_" + UUID.randomUUID().toString().replace("-", "");
        table = schema + ".documents";
        archive = new DocumentArchive(dataSource, table);
        archive.initialize();
        archive.initialize(); // Idempotent startup.
    }

    @AfterEach void cleanup() throws Exception {
        if (dataSource != null) {
            try {
                try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                    s.execute("DROP SCHEMA " + schema + " CASCADE");
                }
            } finally { dataSource.close(); }
        }
    }

    @Test void duplicateUrlKeepsIdAndCreationInstantWhileUpdatingContent() throws Exception {
        String url = "https://example.org/文档?q=1";
        String hash = hash(url);
        long created = 1787941534225L;
        long id = archive.upsert(url, hash, "旧标题", "旧内容", created);
        long sameId = archive.upsert(url, hash, "新标题", "正文🙂\n".repeat(20000), created + 12345);
        assertEquals(id, sameId);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT *, count(*) OVER() AS total FROM " + table)) {
            assertTrue(r.next());
            assertEquals(1, r.getLong("total"));
            assertEquals(url, r.getString("url"));
            assertEquals(hash, r.getString("url_hash"));
            assertEquals("新标题", r.getString("title"));
            assertEquals("正文🙂\n".repeat(20000), r.getString("content"));
            assertEquals(created, r.getObject("created_at", OffsetDateTime.class).toInstant().toEpochMilli());
            assertEquals(created + 12345, r.getObject("updated_at", OffsetDateTime.class).toInstant().toEpochMilli());
            assertEquals("unspecified", r.getString("content_type"));
            assertFalse(r.next());
        }
    }

    @Test void importedIdRemainsUsableAndNullsStayNull() throws Exception {
        String url = "https://example.org/import";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO " + table + " (id, url, url_hash, created_at, updated_at) VALUES (281, ?, ?, ?, ?)")) {
            s.setString(1, url);
            s.setString(2, hash(url));
            s.setObject(3, OffsetDateTime.parse("2026-10-21T00:00:00.123Z"));
            s.setObject(4, OffsetDateTime.parse("2026-10-21T00:00:00.123Z"));
            s.executeUpdate();
        }
        assertEquals(281, archive.upsert(url, hash(url), null, null, 1792540800456L));
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT title, content FROM " + table + " WHERE id = 281")) {
            assertTrue(r.next());
            assertNull(r.getString(1));
            assertNull(r.getString(2));
        }
    }

    @Test void databaseFailurePropagatesAndUnsafeTableNamesAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new DocumentArchive(dataSource, "documents; DROP TABLE other"));
        assertThrows(SQLException.class, () -> archive.upsert("url", "invalid hash", "title", "content", 0));
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*) FROM " + table)) {
            r.next();
            assertEquals(0, r.getLong(1));
        }
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
