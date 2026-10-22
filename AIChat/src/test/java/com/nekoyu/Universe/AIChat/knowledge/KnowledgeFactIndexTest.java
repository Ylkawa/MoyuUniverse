package com.nekoyu.Universe.AIChat.knowledge;

import io.grpc.*;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real Qdrant client requests against an in-memory gRPC channel; no live services. */
class KnowledgeFactIndexTest {
    private static final long NOW = 1_790_000_000_000L;
    private static final List<Float> VECTOR = List.of(0.3f, 0.7f);

    @Test void twoUrlsSupportOneFactWithoutBoostingConfidenceOrExtendingOtherSources() throws Exception {
        Fixture f = new Fixture();
        f.replace(1, fact("Model A price in region R on date D?", "$2/$10", 7), NOW);
        var first = f.only();
        f.replace(2, fact("Model A price in region R on date D?", "$2/$10", 30), NOW + 1);
        var merged = f.only();
        assertEquals(first.getId(), merged.getId());
        assertEquals(first.getVectors(), merged.getVectors());
        assertEquals(2, f.sources(merged).size());
        assertEquals(List.of(url(1), url(2)), strings(merged.getPayloadOrThrow("source_urls")));
        assertEquals(2, merged.getPayloadOrThrow("document_ids").getListValue().getValuesCount());
        assertEquals(0.85, merged.getPayloadOrThrow("confidence").getDoubleValue(), 0.0001);
        assertEquals(NOW, merged.getPayloadOrThrow("created_at").getIntegerValue());
        assertEquals(NOW + 7 * 86_400_000L, f.sources(merged).get(0).expiresAt());
        assertEquals(NOW + 1 + 30 * 86_400_000L, f.sources(merged).get(1).expiresAt());
        assertEquals(0, f.matchCalls, "exact duplicates need no model round trip");
    }

    @Test void paraphraseUsesMatcherButDifferentVersionOrConclusionStaysSeparate() throws Exception {
        Fixture f = new Fixture();
        f.replace(1, fact("Model A v1 price?", "$2/$10", 30), NOW);
        f.matcher = (incoming, candidates) -> incoming.conclusion().equals(candidates.get(0).conclusion()) ? 0 : -1;
        f.replace(2, fact("How much does Model A v1 cost?", "$2/$10", 30), NOW + 1);
        assertEquals(1, f.points.size());
        assertEquals(1, f.matchCalls);
        f.replace(3, fact("Model A v2 price?", "$4/$20", 30), NOW + 2);
        assertEquals(2, f.points.size());
        assertEquals(2, f.matchCalls);
    }

    @Test void reindexingEitherSourceOnlyRemovesItsOwnObsoleteSupport() throws Exception {
        Fixture f = new Fixture();
        var original = fact("Model A price?", "$2/$10", 30);
        f.replace(1, original, NOW);
        f.replace(2, original, NOW + 1);
        f.replace(1, fact("Model A price?", "$4/$20", 30), NOW + 2);
        assertEquals(2, f.points.size());
        var old = f.byConclusion("$2/$10");
        assertEquals(List.of(url(2)), f.sources(old).stream().map(KnowledgeStore.KnowledgeSource::url).toList());
        assertEquals(2, old.getPayloadOrThrow("document_id").getIntegerValue());
        f.replace(2, fact("Model A price?", "$4/$20", 30), NOW + 3);
        assertEquals(1, f.points.size(), "last obsolete source removal deletes only the unsupported fact");
        assertEquals(2, f.sources(f.only()).size());
    }

    @Test void sameSourceRetryIsIdempotentAndSecondaryMembershipIsFound() throws Exception {
        Fixture f = new Fixture();
        var entry = fact("Model A price?", "$2/$10", 30);
        f.replace(1, entry, NOW);
        f.replace(2, entry, NOW + 1);
        f.replace(2, entry, NOW + 2);
        assertEquals(1, f.points.size());
        assertEquals(2, f.sources(f.only()).size());
        assertEquals(2, f.lastScroll.getFilter().getShould(1).getField().getMatch().getInteger());
    }

    @Test void expiryOfOneSourceDoesNotHideOrRefreshAnotherSource() throws Exception {
        Fixture f = new Fixture();
        f.replace(1, fact("Model A price?", "$2/$10", 7), NOW);
        f.replace(2, fact("Model A price?", "$2/$10", 30), NOW + 1);
        long later = NOW + 8 * 86_400_000L;
        assertEquals(List.of(url(2)), f.index.activeSources(f.only().getPayloadMap(), later).stream()
                .map(KnowledgeStore.KnowledgeSource::url).toList());
        f.replace(3, fact("Model A price?", "$2/$10", 30), later);
        assertEquals(1, f.points.size());
        assertEquals(2, f.only().getPayloadOrThrow("document_id").getIntegerValue());
        assertEquals(List.of(url(2), url(3)), f.index.activeSources(f.only().getPayloadMap(), later).stream()
                .map(KnowledgeStore.KnowledgeSource::url).toList());
        assertEquals(NOW + 7 * 86_400_000L, f.sources(f.only()).get(0).expiresAt());
    }

    @Test void partialBatchFailureRetainsOldFactsAndRetryConverges() throws Exception {
        Fixture f = new Fixture();
        f.replace(1, fact("Old question?", "Old answer", 30), NOW);
        var oldId = f.only().getId();
        var entries = List.of(fact("New question A?", "New answer A", 30), fact("New question B?", "New answer B", 30));
        f.writesUntilFailure = 1;
        assertThrows(IOException.class, () -> f.index.replace(1, url(1), "Source 1", entries, List.of(VECTOR, VECTOR), NOW + 1));
        assertTrue(f.points.containsKey(oldId));
        assertEquals(2, f.points.size());
        f.writesUntilFailure = -1;
        f.index.replace(1, url(1), "Source 1", entries, List.of(VECTOR, VECTOR), NOW + 2);
        assertFalse(f.points.containsKey(oldId));
        assertEquals(2, f.points.size());
        for (var point : f.points.values()) assertEquals(1, f.sources(point).size());
    }

    @Test void failedWriteOrInvalidMatchCannotRemovePreviouslyStoredSupport() throws Exception {
        Fixture f = new Fixture();
        f.replace(1, fact("Model A price?", "$2/$10", 30), NOW);
        var oldId = f.only().getId();
        f.failWrite = true;
        assertThrows(IOException.class, () -> f.replace(1, fact("Model A price?", "$4/$20", 30), NOW + 1));
        assertTrue(f.points.containsKey(oldId));
        assertEquals(1, f.sources(f.only()).size());
        f.failWrite = false;
        f.matcher = (entry, candidates) -> 99;
        assertThrows(IOException.class, () -> f.replace(1, fact("Model A price?", "$4/$20", 30), NOW + 2));
        assertTrue(f.points.containsKey(oldId));
        f.matcher = (entry, candidates) -> { throw new IOException("offline classifier unavailable"); };
        assertThrows(IOException.class, () -> f.replace(1, fact("Model A price?", "$4/$20", 30), NOW + 3));
        assertTrue(f.points.containsKey(oldId));
    }

    @Test void legacySingleDocumentPayloadUpgradesOnMergeWithoutChangingItsVectorOrId() throws Exception {
        Fixture f = new Fixture();
        Common.PointId id = Common.PointId.newBuilder().setUuid(UUID.randomUUID().toString()).build();
        var legacy = Points.PointStruct.newBuilder().setId(id)
                .setVectors(io.qdrant.client.VectorsFactory.namedVectors(Map.of("vector", io.qdrant.client.VectorFactory.vector(VECTOR))))
                .putPayload("document_id", integer(8)).putPayload("query", text("Model A price?"))
                .putPayload("conclusion", text("$2/$10")).putPayload("confidence", Value.newBuilder().setDoubleValue(0.9).build())
                .putPayload("ttl", integer(NOW + 86_400_000L)).putPayload("created_at", integer(NOW - 100)).build();
        f.points.put(id, legacy);
        f.replace(9, fact("Model A price?", "$2/$10", 30), NOW);
        assertEquals(id, f.only().getId());
        assertEquals(legacy.getVectors(), f.only().getVectors());
        assertEquals(List.of(url(8), url(9)), strings(f.only().getPayloadOrThrow("source_urls")));
        assertEquals(NOW - 100, f.only().getPayloadOrThrow("created_at").getIntegerValue());
    }

    @Test void matchSelectionRejectsOutOfRangeFractionalAndUnstructuredAnswers() {
        assertEquals(-1, KnowledgeSources.parseMatchIndex("{\"match_index\":-1}", 2));
        assertEquals(1, KnowledgeSources.parseMatchIndex("{\"match_index\":1}", 2));
        for (String answer : List.of("{\"match_index\":2}", "{\"match_index\":-2}", "{\"match_index\":0.5}",
                "{\"match_index\":\"0\"}", "{\"match_index\":true}", "{\"match_index\":0,\"extra\":1}", "[]"))
            assertThrows(RuntimeException.class, () -> KnowledgeSources.parseMatchIndex(answer, 2), answer);
    }

    private static KnowledgeQueryParser.Entry fact(String question, String answer, int days) {
        return new KnowledgeQueryParser.Entry(question, answer, 0.85f, days);
    }
    private static String url(long id) { return "https://source" + id + ".invalid/article"; }
    private static Value text(String s) { return Value.newBuilder().setStringValue(s).build(); }
    private static Value integer(long n) { return Value.newBuilder().setIntegerValue(n).build(); }
    private static List<String> strings(Value list) { return list.getListValue().getValuesList().stream().map(Value::getStringValue).toList(); }

    private static class Fixture {
        final Map<Common.PointId, Points.PointStruct> points = new LinkedHashMap<>();
        boolean failWrite;
        int writesUntilFailure = -1;
        int matchCalls;
        Points.ScrollPoints lastScroll;
        KnowledgeFactIndex.Matcher matcher = (incoming, candidates) -> -1;
        final KnowledgeFactIndex index;

        Fixture() {
            var client = new QdrantClient(QdrantGrpcClient.newBuilder(new OfflineChannel(this), false, false).build());
            index = new KnowledgeFactIndex(client, "knowledge-test", "vector", 10,
                    id -> new KnowledgeStore.KnowledgeSource(id, url(id), "Source " + id, "", "", 0, 0),
                    (entry, candidates) -> { matchCalls++; return matcher.select(entry, candidates); });
        }
        void replace(long id, KnowledgeQueryParser.Entry entry, long now) throws IOException {
            index.replace(id, url(id), "Source " + id, List.of(entry), List.of(VECTOR), now);
        }
        Points.PointStruct only() { assertEquals(1, points.size()); return points.values().iterator().next(); }
        Points.PointStruct byConclusion(String conclusion) {
            return points.values().stream().filter(p -> p.getPayloadOrThrow("conclusion").getStringValue().equals(conclusion)).findFirst().orElseThrow();
        }
        List<KnowledgeStore.KnowledgeSource> sources(Points.PointStruct p) throws IOException { return index.sources(p.getPayloadMap()); }

        Object rpc(Object request) {
            if (request instanceof Points.SearchPoints search) {
                var response = Points.SearchResponse.newBuilder();
                long now = (long) search.getFilter().getMust(0).getField().getRange().getGte();
                points.values().stream().filter(p -> p.getPayloadOrThrow("ttl").getIntegerValue() >= now)
                        .limit(search.getLimit()).forEach(p -> response.addResult(Points.ScoredPoint.newBuilder()
                                .setId(p.getId()).setScore(0.9f).putAllPayload(p.getPayloadMap())));
                return response.build();
            }
            if (request instanceof Points.ScrollPoints scroll) {
                lastScroll = scroll;
                long doc = scroll.getFilter().getShould(0).getField().getMatch().getInteger();
                var response = Points.ScrollResponse.newBuilder();
                points.values().stream().filter(p -> p.getPayloadOrThrow("document_id").getIntegerValue() == doc
                        || p.getPayloadMap().getOrDefault("document_ids", Value.getDefaultInstance()).getListValue()
                        .getValuesList().stream().anyMatch(v -> v.getIntegerValue() == doc))
                        .forEach(p -> response.addResult(Points.RetrievedPoint.newBuilder().setId(p.getId()).putAllPayload(p.getPayloadMap())));
                return response.build();
            }
            if (request instanceof Points.UpsertPoints upsert) {
                assertTrue(upsert.getWait());
                upsert.getPointsList().forEach(p -> points.put(p.getId(), p));
            } else if (request instanceof Points.SetPayloadPoints payload) {
                assertTrue(payload.getWait());
                for (var id : payload.getPointsSelector().getPoints().getIdsList())
                    points.computeIfPresent(id, (key, p) -> p.toBuilder().putAllPayload(payload.getPayloadMap()).build());
            } else if (request instanceof Points.DeletePoints delete) {
                assertTrue(delete.getWait());
                delete.getPoints().getPoints().getIdsList().forEach(points::remove);
            } else throw new AssertionError("Unexpected offline RPC " + request.getClass());
            return Points.PointsOperationResponse.newBuilder().setResult(Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Completed)).build();
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
                @SuppressWarnings("unchecked") @Override public void halfClose() {
                    boolean write = !(request instanceof Points.SearchPoints) && !(request instanceof Points.ScrollPoints);
                    if (write && (fixture.failWrite || fixture.writesUntilFailure == 0)) {
                        listener.onClose(Status.UNAVAILABLE, new Metadata());
                        return;
                    }
                    if (write && fixture.writesUntilFailure > 0) fixture.writesUntilFailure--;
                    listener.onMessage((RespT) fixture.rpc(request));
                    listener.onClose(Status.OK, new Metadata());
                }
            };
        }
    }
}
