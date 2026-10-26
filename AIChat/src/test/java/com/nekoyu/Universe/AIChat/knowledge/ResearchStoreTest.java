package com.nekoyu.Universe.AIChat.knowledge;

import com.google.gson.JsonObject;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

import static com.nekoyu.Universe.AIChat.knowledge.LibrarianResearchTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Offline JDBC regressions for reports, evidence and topic embeddings. */
class ResearchStoreTest {
    private static final String URL = "https://example.invalid/source";

    @Test void archiveRoundTripKeepsIdTimesRequestAndObservedEvidence() throws Exception {
        Fixture f = new Fixture();
        Researches first = research("原主题", "有来源的完整报告：" + URL);
        f.store.archiveResearch(first);
        f.store.archiveResearch(first);
        assertEquals(1, f.rows.size(), "重试同一次归档不创建新任务");
        assertEquals(first.id(), f.lastVectorId);
        f.hits = List.of(first.id());
        Researches loaded = f.store.searchResearches("不同表述的相似主题").get(0);
        assertEquals(List.of("原主题", "原主题", "不同表述的相似主题"), f.embedded);
        assertEquals(first.id(), loaded.id());
        assertEquals(first.createdAt(), loaded.createdAt());
        assertEquals(first.completedAt(), loaded.completedAt());
        assertEquals(first.report(), loaded.report());
        assertEquals(first.lastRequest(), loaded.lastRequest());
        assertEquals(first.evidence(), loaded.evidence(), "来源证据须原样往返，不从对话重新推测");
        assertEquals(0.4, f.lastMaximumDistance, 0.00001);
        assertTrue(f.lastSearchSql.contains("LIMIT 3"));
    }

    @Test void searchPreservesVectorOrderAndSkipsDuplicateMissingAndFailedRecords() throws Exception {
        Fixture f = new Fixture();
        Researches first = research("主题一", "报告一"), second = research("主题二", "报告二");
        f.store.archiveResearch(first);
        f.store.archiveResearch(second);
        UUID failed = UUID.randomUUID();
        f.rows.put(failed, new HashMap<>(f.rows.get(first.id())));
        f.rows.get(failed).put("status", "failed");
        f.hits = List.of(second.id(), failed, UUID.randomUUID(), second.id(), first.id());
        assertEquals(List.of(second.id(), first.id()), f.store.searchResearches("查询").stream()
                .map(Researches::id).toList());
        assertTrue(f.store.searchResearches(" ").isEmpty());
    }

    @Test void vectorFailureKeepsSqlReportAndSqlFailureDoesNotWriteVector() throws Exception {
        Fixture f = new Fixture();
        Researches research = research("主题", "完整报告");
        f.failUpsert = true;
        assertThrows(IOException.class, () -> f.store.archiveResearch(research));
        assertEquals(research.report(), f.rows.get(research.id()).get("report"));
        assertNotNull(f.lastVectorId);
        f.lastVectorId = null;
        f.failSql = true;
        assertThrows(IOException.class, () -> f.store.archiveResearch(research("另一主题", "另一报告")));
        assertNull(f.lastVectorId);
    }

    @Test void invalidEmbeddingNeverReachesVectorServiceButReportIsSaved() {
        Fixture f = new Fixture();
        f.embeddingValues = new double[]{Double.NaN, 1};
        assertThrows(IOException.class, () -> f.store.archiveResearch(research("主题", "报告")));
        assertEquals(1, f.rows.size());
        assertNull(f.lastVectorId);
    }

    @Test void librarianUsesStoreForHistoryAndArchivesItsFinalRequest() throws Exception {
        Fixture f = new Fixture();
        Researches previous = research("历史主题", "旧版报告 " + URL);
        f.store.archiveResearch(previous);
        f.hits = List.of(previous.id());
        var cfg = config(null);
        cfg.enableAsyncUpdate = false;
        Librarian librarian = new Librarian(provider((messages, request) -> {
            assertTrue(messages.stream().anyMatch(m -> m.toString().contains("旧版报告")
                    && m.toString().contains("不代表本轮重新核查")));
            return response("新版完整报告 " + URL, "stop");
        }), cfg, question -> "", result -> fail("知识更新已关闭"), f.store);
        assertEquals("新版完整报告 " + URL, librarian.search("新版主题", "本次问题"));
        assertEquals(2, f.rows.size());
        var row = f.rows.values().stream().filter(r -> "新版主题".equals(r.get("topic"))).findFirst().orElseThrow();
        JsonObject snapshot = com.google.gson.JsonParser.parseString((String) row.get("last_request")).getAsJsonObject();
        assertEquals("offline-research", snapshot.get("model").getAsString());
        assertEquals(Set.of("model", "messages", "tools", "options"), snapshot.keySet(),
                "证据目录独立存储，不改变逻辑模型请求");
        assertTrue(snapshot.getAsJsonArray("messages").toString().contains("旧版报告"));
        assertFalse(snapshot.toString().contains("新版完整报告"), "保存的是最后一次请求，而非响应后的上下文");
    }

    @Test void historicalReportCannotCreateObservedEvidenceAcrossArchiveRoundTrips() throws Exception {
        Fixture f = new Fixture();
        String invented = "https://invented.invalid/not-observed";
        Researches previous = new Researches("历史主题", "原始问题中没有链接", "历史模型报告引用了 " + invented,
                Map.of(), 1000);
        f.store.archiveResearch(previous);
        f.hits = List.of(previous.id());
        assertTrue(f.store.searchResearches("历史主题").get(0).evidence().isEmpty());
        var cfg = config(null);
        cfg.enableAsyncUpdate = false;
        Librarian librarian = new Librarian(provider((messages, request) -> {
            assertTrue(messages.stream().anyMatch(m -> "user".equals(m.role)
                    && m.toString().contains("历史模型报告引用了 " + invented)));
            return response("本轮报告沿用了历史链接 " + invented, "stop");
        }), cfg, question -> "", result -> fail("知识更新已关闭"), f.store);

        librarian.search("本轮主题", "本轮问题也没有链接");
        UUID currentId = f.rows.keySet().stream().filter(id -> !id.equals(previous.id())).findFirst().orElseThrow();
        f.hits = List.of(currentId);
        Researches loaded = f.store.searchResearches("本轮主题").get(0);
        assertTrue(loaded.lastRequest().getAsJsonArray("messages").toString().contains(invented));
        assertEquals("{}", f.rows.get(currentId).get("evidence"), "显式空证据与旧记录 NULL 分开存储");
        assertTrue(loaded.evidence().isEmpty(), "系统加入 user 上下文的历史报告不能扩张已观察来源");
    }

    @Test void legacyNullEvidenceRestoresOnlyOriginalQuestionAndRealTools() throws Exception {
        Fixture f = new Fixture();
        String inputUrl = "https://example.invalid/input", invented = "https://invented.invalid/history";
        Message history = new Message("历史模型报告引用 " + invented);
        history.role = "user";
        Message call = new Message("助手自行生成的链接 " + invented);
        call.role = "assistant";
        Tool_call tool = new Tool_call();
        tool.id = "read-legacy";
        tool.function.name = "read";
        tool.function.arguments = "{\"url\":\"" + URL + "\"}";
        call.tool_calls = new Tool_call[]{tool};
        Message result = new Message("真实工具返回的来源摘要");
        result.role = "tool";
        result.tool_call_id = tool.id;
        JsonObject request = ResearchStore.snapshotRequest("test", List.of(history, call, result), List.of(),
                new CompletionsRequest());
        Researches legacy = new Researches(UUID.randomUUID(), "旧主题", "原始输入提供 " + inputUrl,
                "报告引用 " + inputUrl + " " + URL + " " + invented, Map.of(), 1, 2, request);
        f.store.archiveResearch(legacy);
        f.rows.get(legacy.id()).put("evidence", null); // 模拟迁移前没有独立证据字段的记录。
        f.hits = List.of(legacy.id());

        Researches loaded = f.store.searchResearches("旧主题").get(0);
        assertEquals(Set.of(inputUrl, URL), loaded.evidence().keySet());
        assertTrue(loaded.evidence().get(inputUrl).contains("输入提供的材料，未核对原文"));
        assertTrue(loaded.evidence().get(URL).contains("真实工具返回的来源摘要"));
        assertEquals(request, loaded.lastRequest());
    }

    @Test void explicitEmptyOrInvalidEvidenceNeverFallsBackToRequest() throws Exception {
        Fixture f = new Fixture();
        Message source = new Message("输入来源 " + URL);
        source.role = "user";
        JsonObject request = ResearchStore.snapshotRequest("test", List.of(source), List.of(), new CompletionsRequest());
        Researches research = new Researches(UUID.randomUUID(), "主题", "原始问题含 " + URL,
                "报告含 " + URL, Map.of(), 1, 2, request);
        f.store.archiveResearch(research);
        f.hits = List.of(research.id());
        assertTrue(f.store.searchResearches("主题").get(0).evidence().isEmpty(), "{} 不按旧记录恢复来源");
        f.rows.get(research.id()).put("evidence", "{\"" + URL + "\":123}");
        assertThrows(IOException.class, () -> f.store.searchResearches("主题"), "损坏的目录不得回退扩张来源");
    }

    @Test void snapshotIsIndependentAndOnlyObservedSourcesAreRestored() {
        Message call = new Message("助手编造的链接 https://invented.invalid");
        call.role = "assistant";
        Tool_call tool = new Tool_call();
        tool.id = "read-1";
        tool.function.name = "read";
        tool.function.arguments = "{\"url\":\"" + URL + "\"}";
        call.tool_calls = new Tool_call[]{tool};
        Message result = new Message("只有摘要，未核对原文");
        result.role = "tool";
        result.tool_call_id = tool.id;
        var messages = new ArrayList<>(List.of(call, result));
        var options = new CompletionsRequest();
        options.toolChoice = "none";
        options.asyncToolSink = (id, timeout) -> {};
        var snapshot = ResearchStore.snapshotRequest("model", messages, List.of(), options);
        messages.clear();
        options.toolChoice = "auto";
        assertEquals(2, snapshot.getAsJsonArray("messages").size());
        assertEquals("none", snapshot.getAsJsonObject("options").get("toolChoice").getAsString());
        assertFalse(snapshot.toString().contains("asyncToolSink"));
        var evidence = ResearchStore.restoreEvidence("问题", URL + " https://invented.invalid", snapshot);
        assertEquals(Set.of(URL), evidence.keySet());
        assertTrue(evidence.get(URL).contains("只有摘要"));
        assertTrue(ResearchStore.restoreEvidence("无链接", URL, null).isEmpty());
        Researches research = new Researches(UUID.randomUUID(), "主题", "问题", "报告", Map.of(), 1, 2, snapshot);
        research.lastRequest().addProperty("changed", true);
        assertFalse(research.lastRequest().has("changed"));
    }

    private static Researches research(String topic, String report) {
        Message source = new Message("只有摘要，未核对原文 " + URL);
        source.role = "user";
        JsonObject request = ResearchStore.snapshotRequest("test", List.of(source), List.of(), new CompletionsRequest());
        return new Researches(UUID.randomUUID(), topic, "原始问题", report, Map.of(URL, source.toString()),
                1792540800123L, 1792540810456L, request);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static class Fixture {
        final Map<UUID, Map<String, Object>> rows = new LinkedHashMap<>();
        final List<String> embedded = new ArrayList<>();
        List<UUID> hits = List.of();
        double[] embeddingValues = {0.3, 0.7};
        boolean failSql, failUpsert;
        UUID lastVectorId;
        String lastSearchSql;
        double lastMaximumDistance;
        final ResearchStore store;

        Fixture() {
            DataSource ds = proxy(DataSource.class, (object, method, args) -> {
                if (!method.getName().equals("getConnection")) throw new UnsupportedOperationException(method.getName());
                if (failSql) throw new SQLException("offline SQL failure");
                return proxy(Connection.class, (c, m, a) -> switch (m.getName()) {
                    case "prepareStatement" -> statement((String) a[0]);
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
            });
            store = new ResearchStore(ds, request -> {
                assertEquals("test-embedding", request.model);
                assertEquals(2, request.dimension);
                embedded.add(request.message.get(0).toString());
                var response = new EmbeddingResponse();
                var data = new EmbeddingResponse.Embedding();
                data.embedding = embeddingValues;
                response.data.add(data);
                return response;
            }, "test-embedding", 2);
        }

        PreparedStatement statement(String sql) {
            Map<Integer, Object> parameters = new HashMap<>();
            return proxy(PreparedStatement.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "setObject", "setString", "setDouble": parameters.put((Integer) args[0], args[1]); return null;
                    case "executeUpdate": {
                        if (sql.startsWith("UPDATE")) {
                            lastVectorId = (UUID) parameters.get(2);
                            if (failUpsert) throw new SQLException("offline vector update failure");
                            rows.get(lastVectorId).put("embedding", parameters.get(1));
                            return 1;
                        }
                        assertTrue(sql.contains("?::jsonb"));
                        UUID id = (UUID) parameters.get(1);
                        Map<String, Object> row = new HashMap<>();
                        String[] names = {"id", "topic", "question", "report", "last_request", "evidence", "created_at", "finished_at"};
                        for (int i = 0; i < names.length; i++) row.put(names[i], parameters.get(i + 1));
                        row.put("status", "completed");
                        rows.put(id, row);
                        return 1;
                    }
                    case "executeQuery": {
                        assertTrue(sql.contains("status = 'completed'"));
                        lastSearchSql = sql;
                        lastMaximumDistance = (Double) parameters.get(2);
                        var selected = hits.stream().distinct().map(rows::get).filter(Objects::nonNull)
                                .filter(row -> "completed".equals(row.get("status")) && row.get("embedding") != null)
                                .limit(3).toList();
                        int[] index = {-1};
                        return proxy(ResultSet.class, (r, m, a) -> switch (m.getName()) {
                            case "next" -> ++index[0] < selected.size();
                            case "getString", "getObject" -> selected.get(index[0]).get((String) a[0]);
                            case "close" -> null;
                            default -> throw new UnsupportedOperationException(m.getName());
                        });
                    }
                    case "close": return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }
    }

}
