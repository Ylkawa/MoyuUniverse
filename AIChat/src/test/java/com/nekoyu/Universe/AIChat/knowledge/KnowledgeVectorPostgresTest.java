package com.nekoyu.Universe.AIChat.knowledge;

import com.nekoyu.Universe.AIChat.PgVectorTable;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeVectorPostgresTest {
    HikariDataSource ds;
    String schema;
    PgVectorTable table;

    @BeforeEach void prepare() throws Exception {
        Assumptions.assumeTrue(System.getenv("PG_URL") != null, "PG_URL required");
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(System.getenv("PG_URL")); pool.setUsername(System.getenv("PG_USER"));
        pool.setPassword(System.getenv("PG_PASSWORD")); pool.setMaximumPoolSize(2);
        ds = new HikariDataSource(pool);
        schema = "knowledge_vector_test_" + UUID.randomUUID().toString().replace("-", "");
        table = new PgVectorTable(ds, schema + ".facts", 3);
        table.initialize();
    }
    @AfterEach void cleanup() throws Exception {
        if (ds != null) {
            try (Connection c = ds.getConnection(); Statement s = c.createStatement()) { s.execute("DROP SCHEMA " + schema + " CASCADE"); }
            finally { ds.close(); }
        }
    }

    @Test void sharedSourcesMembershipExpiryAndReplacementUseRealSql() throws Exception {
        KnowledgeFactIndex index = new KnowledgeFactIndex(table, 10,
                id -> new KnowledgeStore.KnowledgeSource(id, "https://source/" + id, "title", "", "", 0, 0),
                (incoming, candidates) -> -1);
        long now = System.currentTimeMillis();
        var fact = new KnowledgeQueryParser.Entry("question", "answer", .8f, 7);
        List<Float> vector = List.of(1f, 0f, 0f);
        index.replace(1, "https://source/1", "one", List.of(fact), List.of(vector), now);
        index.replace(2, "https://source/2", "two", List.of(fact), List.of(vector), now + 1);
        assertEquals(1, table.list("true", List.of(), "id", 10).size());
        assertEquals(1, index.documentSources(2).size(), "secondary membership must be found in JSONB");
        var hit = table.search(vector, "(payload->>'ttl')::bigint >= ?", List.of(now), 10, .6).get(0);
        assertEquals(2, index.activeSources(hit.payload(), now).size());
        assertTrue(table.search(vector, "(payload->>'ttl')::bigint >= ?", List.of(now + 8 * 86_400_000L), 10, .6).isEmpty());
        index.replace(2, "https://source/2", "two", List.of(new KnowledgeQueryParser.Entry("question", "changed", .7f, 30)), List.of(vector), now + 2);
        assertEquals("answer", index.documentSources(1).get(0).conclusion());
        assertEquals("changed", index.documentSources(2).get(0).conclusion());
        assertEquals(2, table.list("true", List.of(), "id", 10).size());
    }

    @Test void researchReportEvidenceAndTopicVectorRoundTripInOneTable() throws Exception {
        String researchTable = schema + ".research_task";
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE " + researchTable + " (id uuid PRIMARY KEY, topic text, question text, status text, report text, last_request jsonb, evidence jsonb, error text, created_at timestamptz, finished_at timestamptz, embedding vector(3))");
        }
        ResearchStore store = new ResearchStore(ds, request -> {
            var response = new EmbeddingResponse();
            var value = new EmbeddingResponse.Embedding();
            value.embedding = new double[]{1, 0, 0}; response.data.add(value); return response;
        }, "test", 3, researchTable);
        Researches original = new Researches(UUID.randomUUID(), "主题", "原始问题", "完整报告",
                Map.of("https://source/1", "真实观察"), 1792540800123L, 1792540810456L, null);
        store.archiveResearch(original);
        store.archiveResearch(original);
        Researches loaded = store.searchResearches("近似主题").get(0);
        assertEquals(original.id(), loaded.id()); assertEquals(original.report(), loaded.report());
        assertEquals(original.evidence(), loaded.evidence()); assertEquals(original.createdAt(), loaded.createdAt());
        assertEquals(original.completedAt(), loaded.completedAt());
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("UPDATE " + researchTable + " SET status='failed'");
        }
        assertTrue(store.searchResearches("主题").isEmpty());
    }
}
