package com.nekoyu.Universe.AIChat.knowledge;

import com.nekoyu.Universe.AIChat.Config;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.Instant;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeVectorPostgresTest {
    HikariDataSource ds;
    String schema;
    KnowledgeFactTable table;

    @BeforeEach void prepare() throws Exception {
        Assumptions.assumeTrue(System.getenv("PG_URL") != null, "PG_URL required");
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(System.getenv("PG_URL")); pool.setUsername(System.getenv("PG_USER"));
        pool.setPassword(System.getenv("PG_PASSWORD")); pool.setMaximumPoolSize(2);
        ds = new HikariDataSource(pool);
        schema = "knowledge_vector_test_" + UUID.randomUUID().toString().replace("-", "");
        table = new KnowledgeFactTable(ds, schema + ".facts", 3);
        table.initialize();
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
                (incoming, candidates) -> -1);
        long now = System.currentTimeMillis();
        var fact = new KnowledgeQueryParser.Entry("question", "answer", .8f, 7);
        List<Float> vector = List.of(1f, 0f, 0f);
        index.replace(1, List.of(fact), List.of(vector), Instant.ofEpochMilli(now));
        index.replace(2, List.of(fact), List.of(vector), Instant.ofEpochMilli(now + 1));
        assertEquals(1, table.search(vector, Instant.ofEpochMilli(now), 10, .6).size());
        assertEquals(1, index.documentSources(2).size(), "secondary membership must be found in JSONB");
        var hit = table.search(vector, Instant.ofEpochMilli(now), 10, .6).get(0);
        assertEquals(2, hit.fact().activeSources(Instant.ofEpochMilli(now)).size());
        assertTrue(table.search(vector, Instant.ofEpochMilli(now + 8 * 86_400_000L), 10, .6).isEmpty());
        index.replace(2, List.of(new KnowledgeQueryParser.Entry("question", "changed", .7f, 30)), List.of(vector), Instant.ofEpochMilli(now + 2));
        assertEquals("answer", index.documentSources(1).get(0).conclusion());
        assertEquals("changed", index.documentSources(2).get(0).conclusion());
        assertEquals(2, table.search(vector, Instant.ofEpochMilli(now), 10, .6).size());
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

    @Test void typedColumnsAndSourcesRoundTripWithoutPayloadAndUpdatesPreserveCanonicalVector() throws Exception {
        Instant now = Instant.parse("2026-11-02T12:00:00.123Z");
        var source = new KnowledgeFactTable.Source(12, "问题🙂", "结论", .9f, now.plusSeconds(100));
        var fact = new KnowledgeFactTable.Fact(UUID.randomUUID(), source.question(), source.conclusion(),
                source.confidence(), source.reviewDueAt(), now, now, List.of(source));
        table.insert(fact, List.of(1f, 0f, 0f));
        assertEquals(fact, table.forDocument(12).get(0));
        assertTrue(table.forDocument(123).isEmpty());
        assertEquals(1, table.search(List.of(1f, 0f, 0f), source.reviewDueAt(), 10, .6).size());
        assertTrue(table.search(List.of(1f, 0f, 0f), source.reviewDueAt().plusMillis(1), 10, .6).isEmpty());
        assertTrue(table.search(List.of(0f, 1f, 0f), now, 10, .6).isEmpty());
        var renewed = new KnowledgeFactTable.Fact(fact.id(), fact.question(), fact.conclusion(), fact.confidence(),
                fact.reviewDueAt().plusSeconds(100), now, now.plusSeconds(1),
                List.of(new KnowledgeFactTable.Source(12, source.question(), source.conclusion(), source.confidence(), source.reviewDueAt().plusSeconds(100))));
        table.updateSupport(renewed);
        assertEquals(renewed, table.forDocument(12).get(0));
        assertEquals(1, table.search(List.of(1f, 0f, 0f), now, 10, .6).get(0).score(), 1e-6);
        assertThrows(IOException.class, () -> table.insert(fact, List.of(Float.NaN, 0f, 0f)));
        assertThrows(IOException.class, () -> new KnowledgeFactTable(ds, schema + ".facts", 2).initialize());
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema='" + schema + "' AND table_name='facts'")) {
                Set<String> columns = new HashSet<>();
                while (r.next()) columns.add(r.getString(1));
                assertEquals(Set.of("id", "question", "conclusion", "confidence", "embedding", "review_due_at", "created_at", "updated_at", "sources"), columns);
            }
            assertThrows(SQLException.class, () -> s.execute("UPDATE " + schema + ".facts SET confidence=2"));
            assertThrows(SQLException.class, () -> s.execute("UPDATE " + schema + ".facts SET sources='[]'"));
            assertThrows(SQLException.class, () -> s.execute("UPDATE " + schema + ".facts SET question=''"));
        }
        table.delete(fact.id());
        assertTrue(table.forDocument(12).isEmpty());
    }

    @Test void retrievalResolvesSourceUrlAndTitleFromDocuments() throws Exception {
        String documents = schema + ".documents";
        DocumentArchive archive = new DocumentArchive(ds, documents);
        archive.initialize();
        long id = archive.upsert("https://original.invalid", "a".repeat(64), "原始标题", "正文", System.currentTimeMillis());
        var index = new KnowledgeFactIndex(table, 10, (incoming, candidates) -> -1);
        index.replace(id, List.of(new KnowledgeQueryParser.Entry("问题", "结论", .9f, 7)),
                List.of(List.of(1f, 0f, 0f)), Instant.now());
        var cfg = new Config.KnowledgeStore();
        cfg.dimension = 3;
        cfg.documentsTable = documents;
        KnowledgeStore store = new KnowledgeStore(cfg, ds, table, request -> {
            var response = new EmbeddingResponse();
            var value = new EmbeddingResponse.Embedding();
            value.embedding = new double[]{1, 0, 0}; response.data.add(value); return response;
        }, null);
        assertEquals("https://original.invalid", store.search("问题").get(0).factMatches().get(0).sources().get(0).url());
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("UPDATE " + documents + " SET url='https://updated.invalid', title='新标题'");
        }
        var loaded = store.search("问题").get(0);
        assertEquals("https://updated.invalid", loaded.factMatches().get(0).sources().get(0).url());
        assertEquals("新标题", loaded.factMatches().get(0).sources().get(0).title());
        assertEquals("正文", loaded.content);
    }
}
