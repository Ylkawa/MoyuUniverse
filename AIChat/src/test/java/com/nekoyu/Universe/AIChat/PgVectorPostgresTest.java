package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in tests against real pgvector, with all writes isolated in a random schema. */
class PgVectorPostgresTest {
    HikariDataSource ds;
    String schema;
    MemoryTable table;
    Memory memory;
    boolean invalidEmbedding;

    @BeforeEach void prepare() throws Exception {
        Assumptions.assumeTrue(System.getenv("PG_URL") != null, "PG_URL required");
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(System.getenv("PG_URL"));
        pool.setUsername(System.getenv("PG_USER"));
        pool.setPassword(System.getenv("PG_PASSWORD"));
        pool.setMaximumPoolSize(2);
        ds = new HikariDataSource(pool);
        schema = "vector_test_" + UUID.randomUUID().toString().replace("-", "");
        table = new MemoryTable(ds, schema + ".memory", 3);
        table.initialize();
        table.initialize();
        memory = new Memory(request -> {
            var response = new EmbeddingResponse();
            var value = new EmbeddingResponse.Embedding();
            value.embedding = invalidEmbedding ? new double[]{Double.NaN, 0, 0}
                    : request.message.get(0).toString().contains("orthogonal") ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
            response.data.add(value);
            return response;
        }, table, 3);
    }

    @AfterEach void cleanup() throws Exception {
        if (ds != null) {
            try (Connection c = ds.getConnection(); Statement s = c.createStatement()) { s.execute("DROP SCHEMA " + schema + " CASCADE"); }
            finally { ds.close(); }
        }
    }

    @Test void memoryCrudFiltersLocationsHiddenAndDissimilarRecords() throws Exception {
        Memory.Item a = item("same", "user:a", .8f, .9f, 10);
        Memory.Item b = item("same", "user:b", .9f, .8f, 20);
        Memory.Item orthogonal = item("orthogonal", "user:a", 1, 1, 30);
        memory.insert(List.of(a, b, orthogonal));
        assertEquals(List.of(a.id), memory.query(List.of(1f, 0f, 0f), List.of("user:a")).stream().map(i -> i.id).toList());
        assertEquals(2, memory.query(List.of(1f, 0f, 0f), List.of()).size());
        assertEquals(List.of(orthogonal.id, a.id), memory.getLastMemoryItems(List.of("user:a"), 10).stream().map(i -> i.id).toList());
        assertTrue(memory.getLastMemoryItems(Arrays.asList(null, ""), 10).isEmpty());
        assertEquals(orthogonal.id, memory.getHighImportanceItems(List.of("user:a"), 1, .9f).get(0).id);
        memory.update(a.id, .7f, .95f, "updated same");
        Memory.Item updated = memory.query(List.of(1f, 0f, 0f), List.of("user:a")).get(0);
        assertEquals(10, updated.createAt);
        assertEquals("user:a", updated.locationId);
        assertEquals("updated same", updated.content);
        memory.delete(a.id);
        assertTrue(memory.query(List.of(1f, 0f, 0f), List.of("user:a")).isEmpty());
        assertEquals(3, table.list("true", List.of(), "id", 10).size(), "soft deletion preserves data");
    }

    @Test void exactScoresAndSoftDeletionPreserveVector() throws Exception {
        Memory.Item item = item("中文", null, .8f, .5f, 10);
        table.upsert(item, List.of(1f, 0f, 0f));
        assertEquals(1, table.search(List.of(1f, 0f, 0f), "true", List.of(), 10, .6).get(0).score, .000001);
        assertTrue(table.search(List.of(0f, 1f, 0f), "true", List.of(), 10, .6).isEmpty());
        table.hide(item.id);
        var found = table.search(List.of(1f, 0f, 0f), "confidence < 0", List.of(), 10, .6).get(0);
        assertEquals("中文", found.content);
        assertEquals(1, found.score, .000001);
        assertThrows(IOException.class, () -> new MemoryTable(ds, schema + ".memory", 2).initialize());
        assertThrows(IOException.class, () -> table.search(List.of(1f, 0f), "true", List.of(), 10, .6));
    }

    @Test void invalidEmbeddingCannotOverwriteExistingMemory() {
        Memory.Item a = item("same", "user:a", .8f, .9f, 10);
        memory.insert(List.of(a));
        invalidEmbedding = true;
        assertThrows(IllegalStateException.class, () -> memory.update(a.id, .1f, .1f, "broken"));
        assertEquals("same", memory.query(List.of(1f, 0f, 0f), List.of("user:a")).get(0).content);
    }

    private static Memory.Item item(String text, String location, float confidence, float importance, long created) {
        Memory.Item item = new Memory.Item();
        item.content = text; item.locationId = location; item.confidence = confidence;
        item.importance = importance; item.createAt = created;
        return item;
    }
}
