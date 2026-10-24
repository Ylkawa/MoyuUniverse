package com.nekoyu.Universe.AIChat.knowledge;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.nekoyu.Universe.AIChat.knowledge.LibrarianResearchTest.*;

class KnowledgeIngestionTest {
    private static final String URL = "https://example.invalid/source";

    @Test void separateModelExtractsOnlyAllowedSourcesAndAlwaysSubmitsPartialObservations() {
        List<KnowledgeSources.Source> indexed = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        var model = provider((messages, request) -> {
            assertNotNull(request.responseFormat);
            calls.incrementAndGet();
            assertEquals("knowledge_sources", request.responseFormat.jsonSchema.name);
            assertTrue(messages.stream().anyMatch(m -> m.toString().contains("最终完整报告")));
            assertTrue(messages.stream().noneMatch(m -> m.toString().contains("SECRET_REASONING")));
            return response("{\"sources\":[{\"url\":\"" + URL + "\",\"title\":\"标题\",\"content\":\"只有摘要，未读原文\"},"
                    + "{\"url\":\"https://invented.invalid/\",\"title\":\"假来源\",\"content\":\"无依据\"}]}", "stop");
        });
        new KnowledgeIngestion(model, "offline-ingestion", indexed::add)
                .ingest("问题", "最终完整报告 " + URL, Map.of(URL, "实际搜索摘要"));
        assertEquals(1, calls.get());
        assertEquals(1, indexed.size());
        assertEquals(URL, indexed.get(0).url());
    }

    @Test void invalidJsonAndEmptyEvidenceDoNotIndex() {
        AtomicInteger calls = new AtomicInteger(), indexed = new AtomicInteger();
        var model = provider((messages, request) -> { calls.incrementAndGet(); return response("格式错误", "stop"); });
        var ingestion = new KnowledgeIngestion(model, "offline", source -> indexed.incrementAndGet());
        ingestion.ingest("问题", "报告", Map.of());
        assertEquals(0, calls.get());
        assertDoesNotThrow(() -> ingestion.ingest("问题", "报告", Map.of(URL, "摘要")));
        assertEquals(1, calls.get());
        assertEquals(0, indexed.get());
    }

    @Test void repeatedSourceStillReachesIndexForNewFactsRetryAndTtlRefresh() {
        List<KnowledgeSources.Source> indexed = new ArrayList<>();
        var model = provider((messages, request) -> response(
                "{\"sources\":[{\"url\":\"" + URL + "\",\"title\":\"Docs\",\"content\":\"A and new B\"}]}", "stop"));
        var ingestion = new KnowledgeIngestion(model, "offline", indexed::add);
        ingestion.ingest("new features", "A and B " + URL, Map.of(URL, "A and B"));
        ingestion.ingest("verify again", "A and B " + URL, Map.of(URL, "A and B"));
        assertEquals(2, indexed.size(), "SQL existence must not block retry or renewed observation");
    }

    @Test void failedConflictJudgmentCannotAuthorizeSourceRemoval() {
        var ingestion = new KnowledgeIngestion(provider((messages, request) -> response("invalid", "stop")),
                "offline", source -> {});
        assertThrows(java.io.IOException.class, () -> ingestion.isConflicting("A", "B"));
    }

    @Test void ingestionFailureCannotReplaceFinalFeedback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        var librarian = new Librarian(provider((messages, request) -> response("完整最终报告", "stop")),
                config(null), q -> "", result -> { entered.countDown(); throw new IllegalStateException("离线入库失败"); });
        assertEquals("完整最终报告", librarian.search("问题"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
    }
}
