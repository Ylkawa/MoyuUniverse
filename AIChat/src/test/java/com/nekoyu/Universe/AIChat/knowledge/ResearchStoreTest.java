package com.nekoyu.Universe.AIChat.knowledge;

import com.google.gson.JsonObject;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import io.grpc.*;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.nekoyu.Universe.AIChat.knowledge.LibrarianResearchTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Offline JDBC and gRPC interceptors: no database, Qdrant or embedding endpoint is contacted. */
class ResearchStoreTest {
    private static final String URL = "https://example.invalid/source";

    @Test void archiveRoundTripKeepsIdTimesRequestAndObservedEvidence() throws Exception {
        Fixture f = new Fixture();
        f.collectionExists = false;
        Researches first = research("原主题", "有来源的完整报告：" + URL);
        f.store.archiveResearch(first);
        f.store.archiveResearch(first);
        assertEquals(1, f.rows.size(), "重试同一次归档不创建新任务");
        assertTrue(f.lastUpsert.getWait());
        assertEquals(first.id().toString(), f.lastUpsert.getPoints(0).getId().getUuid());
        assertEquals("research-test", f.lastUpsert.getCollectionName());
        assertEquals(2, f.lastCreate.getVectorsConfig().getParamsMap().getMapOrThrow("vector").getSize());
        f.hits = List.of(first.id());
        Researches loaded = f.store.searchResearches("不同表述的相似主题").get(0);
        assertEquals(List.of("原主题", "原主题", "不同表述的相似主题"), f.embedded);
        assertEquals(first.id(), loaded.id());
        assertEquals(first.createdAt(), loaded.createdAt());
        assertEquals(first.completedAt(), loaded.completedAt());
        assertEquals(first.report(), loaded.report());
        assertEquals(first.lastRequest(), loaded.lastRequest());
        assertTrue(loaded.evidence().get(URL).contains("只有摘要"));
        assertEquals(0.6f, f.lastSearch.getScoreThreshold());
        assertEquals(3, f.lastSearch.getLimit());
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
        assertNotNull(f.lastUpsert);
        f.lastUpsert = null;
        f.failSql = true;
        assertThrows(IOException.class, () -> f.store.archiveResearch(research("另一主题", "另一报告")));
        assertNull(f.lastUpsert);
    }

    @Test void invalidEmbeddingNeverReachesVectorServiceButReportIsSaved() {
        Fixture f = new Fixture();
        f.embeddingValues = new double[]{Double.NaN, 1};
        assertThrows(IOException.class, () -> f.store.archiveResearch(research("主题", "报告")));
        assertEquals(1, f.rows.size());
        assertNull(f.lastUpsert);
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
        assertTrue(snapshot.getAsJsonArray("messages").toString().contains("旧版报告"));
        assertFalse(snapshot.toString().contains("新版完整报告"), "保存的是最后一次请求，而非响应后的上下文");
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
        boolean collectionExists = true;
        Collections.CreateCollection lastCreate;
        Points.UpsertPoints lastUpsert;
        Points.SearchPoints lastSearch;
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
            var grpc = QdrantGrpcClient.newBuilder(new OfflineChannel(this), false, false).build();
            store = new ResearchStore(ds, new QdrantClient(grpc), request -> {
                assertEquals("test-embedding", request.model);
                assertEquals(2, request.dimension);
                embedded.add(request.message.get(0).toString());
                var response = new EmbeddingResponse();
                var data = new EmbeddingResponse.Embedding();
                data.embedding = embeddingValues;
                response.data.add(data);
                return response;
            }, "research-test", "vector", "test-embedding", 2);
        }

        PreparedStatement statement(String sql) {
            Map<Integer, Object> parameters = new HashMap<>();
            return proxy(PreparedStatement.class, (object, method, args) -> {
                switch (method.getName()) {
                    case "setObject", "setString": parameters.put((Integer) args[0], args[1]); return null;
                    case "executeUpdate": {
                        assertTrue(sql.contains("?::jsonb"));
                        UUID id = (UUID) parameters.get(1);
                        Map<String, Object> row = new HashMap<>();
                        String[] names = {"id", "topic", "question", "report", "last_request", "created_at", "finished_at"};
                        for (int i = 0; i < names.length; i++) row.put(names[i], parameters.get(i + 1));
                        row.put("status", "completed");
                        rows.put(id, row);
                        return 1;
                    }
                    case "executeQuery": {
                        assertTrue(sql.contains("status = 'completed'"));
                        Map<String, Object> row = rows.get(parameters.get(1));
                        boolean[] read = {false};
                        return proxy(ResultSet.class, (r, m, a) -> switch (m.getName()) {
                            case "next" -> { boolean present = !read[0] && row != null && "completed".equals(row.get("status"));
                                read[0] = true; yield present; }
                            case "getString", "getObject" -> row.get((String) a[0]);
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

    private static class OfflineChannel extends ManagedChannel {
        private final Fixture fixture;
        OfflineChannel(Fixture fixture) { this.fixture = fixture; }
        @Override public String authority() { return "offline"; }
        @Override public ManagedChannel shutdown() { return this; }
        @Override public ManagedChannel shutdownNow() { return this; }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> method, CallOptions options) {
            return new ClientCall<>() {
                Listener<RespT> listener;
                ReqT request;
                @Override public void start(Listener<RespT> listener, Metadata headers) { this.listener = listener; }
                @Override public void request(int count) {}
                @Override public void cancel(String message, Throwable cause) {}
                @Override public void sendMessage(ReqT message) { request = message; }
                @SuppressWarnings("unchecked")
                @Override public void halfClose() {
                    Object response;
                    if (request instanceof Collections.CollectionExistsRequest) {
                        response = Collections.CollectionExistsResponse.newBuilder()
                                .setResult(Collections.CollectionExists.newBuilder().setExists(fixture.collectionExists)).build();
                    } else if (request instanceof Collections.CreateCollection create) {
                        fixture.lastCreate = create;
                        fixture.collectionExists = true;
                        response = Collections.CollectionOperationResponse.newBuilder().setResult(true).build();
                    } else if (request instanceof Points.UpsertPoints upsert) {
                        fixture.lastUpsert = upsert;
                        if (fixture.failUpsert) {
                            listener.onClose(Status.UNAVAILABLE.withDescription("offline vector failure"), new Metadata());
                            return;
                        }
                        response = Points.PointsOperationResponse.newBuilder().setResult(Points.UpdateResult.newBuilder()
                                .setStatus(Points.UpdateStatus.Completed)).build();
                    } else if (request instanceof Points.SearchPoints search) {
                        fixture.lastSearch = search;
                        var builder = Points.SearchResponse.newBuilder();
                        for (UUID id : fixture.hits) builder.addResult(Points.ScoredPoint.newBuilder()
                                .setId(Common.PointId.newBuilder().setUuid(id.toString())).setScore(0.9f));
                        response = builder.build();
                    } else throw new AssertionError("Unexpected offline RPC: " + method.getFullMethodName());
                    listener.onMessage((RespT) response);
                    listener.onClose(Status.OK, new Metadata());
                }
            };
        }
    }
}
