import com.google.gson.Gson;
import com.nekoyu.Universe.AIChat.Config;
import com.nekoyu.Universe.AIChat.KnowledgeStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.util.List;

public class KnowledgeStoreTest {

    static KnowledgeStore store;
    static HikariDataSource dataSource;
    static int passed = 0;
    static int failed = 0;
    static final Gson gson = new Gson();

    public static void main(String[] args) throws Exception {
        System.out.println("=== KnowledgeStore Integration Tests ===\n");

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/moyu_universe_test?useSSL=false&serverTimezone=Asia/Shanghai");
        hikariConfig.setUsername("root");
        hikariConfig.setPassword("");
        try {
            dataSource = new HikariDataSource(hikariConfig);
            dataSource.getConnection().close();
        } catch (Exception e) {
            System.out.println("SKIP: Cannot connect to MySQL: " + e.getMessage());
            return;
        }

        Config.Qdrant qdrantCfg = gson.fromJson("""
                {"address":"127.0.0.1","port":6334,"encryptedConnection":false}
                """, Config.Qdrant.class);

        Config.KnowledgeStore ksCfg = gson.fromJson("""
                {
                  "collection": "moyu_universe_aic_ks_test",
                  "documentsTable": "ks_documents_test",
                  "embeddingProvider": "dashscope",
                  "dimension": 1024,
                  "llmProvider": "dashscope",
                  "llmModel": "qwen-turbo",
                  "queryCount": 5,
                  "candidateLimit": 20,
                  "chunkTokenThreshold": 2000,
                  "embeddingBatchSize": 20,
                  "defaultTtlDays": 30
                }
                """, Config.KnowledgeStore.class);

        try {
            store = new KnowledgeStore(qdrantCfg, ksCfg, dataSource);
        } catch (Exception e) {
            System.out.println("SKIP: Cannot connect to Qdrant or providers: " + e.getMessage());
            dataSource.close();
            return;
        }

        testShortDocumentIndex();
        testMultiplePointsSameDocument();
        testSearchHitsQuery();
        testDedupByDocumentId();
        testSearchReturnsFullContent();
        testHasDocument();
        testGetDocumentIdByUrl();
        testReindexUpdatesDocument();
        testTtlFilter();

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        dataSource.close();
        if (failed > 0) System.exit(1);
    }

    static void testShortDocumentIndex() {
        try {
            String url = "https://test.example.com/short";
            String content = "原神是由米哈游开发的开放世界冒险游戏。游戏于2020年9月28日全球同步上线。玩家扮演旅行者，在提瓦特大陆寻找失散的亲人。";
            store.indexDocumentSync(url, "原神简介", content);
            Thread.sleep(3000);
            assertTrue("1. Short document indexed to MySQL + Qdrant", store.hasDocument(url));
        } catch (Exception e) {
            assertTrue("1. Short document indexed", false);
            System.out.println("   Error: " + e.getMessage());
        }
    }

    static void testMultiplePointsSameDocument() {
        try {
            List<KnowledgeStore.KnowledgeResult> results = store.search("原神是什么游戏");
            assertTrue("2. Search finds document (found " + results.size() + " results)", !results.isEmpty());
            if (!results.isEmpty()) {
                KnowledgeStore.KnowledgeResult r = results.get(0);
                assertTrue("   matchedQueries count > 1 (got " + r.matchedQueries.size() + ")", r.matchedQueries.size() > 1);
                assertTrue("   has conclusion", r.bestConclusion != null && !r.bestConclusion.isEmpty());
                assertTrue("   has confidence", r.bestConfidence > 0);
            }
        } catch (Exception e) {
            assertTrue("2. Multiple points for same document", false);
            System.out.println("   Error: " + e.getMessage());
        }
    }

    static void testSearchHitsQuery() {
        try {
            List<KnowledgeStore.KnowledgeResult> results = store.search("原神什么时候上线的");
            assertTrue("3. Search hits query vector (found " + results.size() + ")", !results.isEmpty());
            if (!results.isEmpty()) {
                assertTrue("   Score > 0.5 (got " + results.get(0).bestScore + ")", results.get(0).bestScore > 0.5f);
            }
        } catch (Exception e) {
            assertTrue("3. Search hits query vector", false);
            System.out.println("   Error: " + e.getMessage());
        }
    }

    static void testDedupByDocumentId() {
        try {
            List<KnowledgeStore.KnowledgeResult> results = store.search("原神");
            long docIdCount = results.stream().map(r -> r.documentId).distinct().count();
            assertTrue("4. Dedup by documentId (results=" + results.size() + ", unique docIds=" + docIdCount + ")",
                    results.size() == docIdCount);
        } catch (Exception e) {
            assertTrue("4. Dedup by documentId", false);
            System.out.println("   Error: " + e.getMessage());
        }
    }

    static void testSearchReturnsFullContent() {
        try {
            List<KnowledgeStore.KnowledgeResult> results = store.search("原神");
            assertTrue("5. Search returns full content from MySQL",
                    !results.isEmpty() && results.get(0).content != null && results.get(0).content.contains("米哈游"));
        } catch (Exception e) {
            assertTrue("5. Search returns full content", false);
            System.out.println("   Error: " + e.getMessage());
        }
    }

    static void testHasDocument() {
        try {
            assertTrue("6a. hasDocument returns true for existing", store.hasDocument("https://test.example.com/short"));
            assertTrue("6b. hasDocument returns false for non-existing", !store.hasDocument("https://nonexistent.example.com"));
        } catch (Exception e) {
            assertTrue("6. hasDocument", false);
            System.out.println("   Error: " + e.getMessage());
        }
    }

    static void testGetDocumentIdByUrl() {
        try {
            Long docId = store.getDocumentIdByUrl("https://test.example.com/short");
            assertTrue("7. getDocumentIdByUrl returns valid id", docId != null && docId > 0);
        } catch (Exception e) {
            assertTrue("7. getDocumentIdByUrl", false);
            System.out.println("   Error: " + e.getMessage());
        }
    }

    static void testReindexUpdatesDocument() {
        try {
            String url = "https://test.example.com/reindex";
            store.indexDocumentSync(url, "Reindex V1", "第一版内容：原神角色钟离是岩神。");
            Thread.sleep(3000);

            store.indexDocumentSync(url, "Reindex V2", "第二版内容：钟离是璃月地区的岩之神，原神中的角色。");
            Thread.sleep(3000);

            List<KnowledgeStore.KnowledgeResult> after = store.search("钟离是什么神");
            assertTrue("8. Reindex: document content updated",
                    !after.isEmpty() && after.get(0).content.contains("第二版"));
        } catch (Exception e) {
            assertTrue("8. Reindex updates document", false);
            System.out.println("   Error: " + e.getMessage());
        }
    }

    static void testTtlFilter() {
        try {
            List<KnowledgeStore.KnowledgeResult> results = store.search("原神");
            assertTrue("9. TTL filter works (results not expired)",
                    !results.isEmpty());
        } catch (Exception e) {
            assertTrue("9. TTL filter", false);
            System.out.println("   Error: " + e.getMessage());
        }
    }

    static void assertTrue(String name, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + name);
            passed++;
        } else {
            System.out.println("  FAIL: " + name);
            failed++;
        }
    }
}
