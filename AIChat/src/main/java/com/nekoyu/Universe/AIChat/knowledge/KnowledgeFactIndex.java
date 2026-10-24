package com.nekoyu.Universe.AIChat.knowledge;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.LongFunction;

import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;

/** One Qdrant point per fact; each source retains its own claim, support and expiry. */
final class KnowledgeFactIndex {
    record Candidate(String question, String conclusion) {}
    @FunctionalInterface interface Matcher {
        int select(KnowledgeQueryParser.Entry incoming, List<Candidate> candidates) throws IOException;
    }
    @FunctionalInterface interface ConflictChecker {
        boolean conflicts(KnowledgeQueryParser.Entry incoming, Candidate ownOld) throws IOException;
    }

    private record FactPoint(Common.PointId id, Map<String, Value> payload, Candidate claim) {}

    private final QdrantClient client;
    private final String collection, vectorName;
    private final int candidateLimit;
    private final LongFunction<KnowledgeStore.KnowledgeSource> documents;
    private final Matcher matcher;

    KnowledgeFactIndex(QdrantClient client, String collection, String vectorName, int candidateLimit,
                       LongFunction<KnowledgeStore.KnowledgeSource> documents, Matcher matcher) {
        this.client = client;
        this.collection = collection;
        this.vectorName = vectorName;
        this.candidateLimit = Math.max(1, Math.min(10, candidateLimit));
        this.documents = documents;
        this.matcher = matcher;
    }

    /** Serialized by the store, including synchronous callers; failed writes never trigger source removal. */
    void replace(long documentId, String url, String title, List<KnowledgeQueryParser.Entry> entries,
                 List<List<Float>> vectors, long now) throws IOException {
        update(documentId, url, title, entries, vectors, now, null);
    }

    /** A research fragment updates only claims it explicitly contradicts, leaving unrelated support intact. */
    void merge(long documentId, String url, String title, List<KnowledgeQueryParser.Entry> entries,
               List<List<Float>> vectors, long now, ConflictChecker conflictChecker) throws IOException {
        update(documentId, url, title, entries, vectors, now, Objects.requireNonNull(conflictChecker));
    }

    private void update(long documentId, String url, String title, List<KnowledgeQueryParser.Entry> entries,
                        List<List<Float>> vectors, long now, ConflictChecker conflictChecker) throws IOException {
        if (entries.size() != vectors.size()) throw new IOException("Fact embedding count mismatch");
        List<Points.RetrievedPoint> previous = documentPoints(documentId);
        Set<Common.PointId> obsolete = new HashSet<>();
        Map<Common.PointId, Map<String, Value>> current = new LinkedHashMap<>();
        for (var point : previous) {
            current.put(point.getId(), point.getPayloadMap());
            if (conflictChecker == null) {
                obsolete.add(point.getId());
                continue;
            }
            // Decide every removal before writing anything. An unavailable classifier cannot remove support.
            for (var source : sources(point.getPayloadMap())) {
                if (source.documentId() != documentId) continue;
                var ownOld = new Candidate(source.question(), source.conclusion());
                for (var incoming : entries) {
                    if (sameClaim(incoming, ownOld)) continue;
                    if (conflictChecker.conflicts(incoming, ownOld)) obsolete.add(point.getId());
                }
            }
        }
        Set<Common.PointId> retained = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            var incoming = new KnowledgeStore.KnowledgeSource(documentId, url, title, entry.question(),
                    entry.conclusion(), entry.confidence(), now + entry.ttlDays() * 86_400_000L);
            List<FactPoint> eligible = new ArrayList<>();
            Set<Common.PointId> seen = new HashSet<>();
            // A source can renew its own expired fact. Also include points written earlier in this batch.
            for (var point : current.entrySet()) {
                for (var source : sources(point.getValue())) {
                    if (source.documentId() != documentId) continue;
                    eligible.add(new FactPoint(point.getKey(), point.getValue(),
                            new Candidate(source.question(), source.conclusion())));
                    seen.add(point.getKey());
                    break;
                }
            }
            List<Points.ScoredPoint> candidates = await(client.searchAsync(Points.SearchPoints.newBuilder()
                    .setCollectionName(collection).setVectorName(vectorName).addAllVector(vectors.get(i))
                    .setLimit(candidateLimit).setScoreThreshold(0.6f)
                    .setFilter(Common.Filter.newBuilder().addMust(range("ttl", now)))
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true)).build()));
            for (var candidate : candidates) {
                if (!seen.add(candidate.getId())) continue;
                var active = activeSources(candidate.getPayloadMap(), now);
                if (active.isEmpty()) continue;
                // The current live representative, rather than an expired source, determines equivalence.
                var representative = active.get(0);
                eligible.add(new FactPoint(candidate.getId(), candidate.getPayloadMap(),
                        new Candidate(representative.question(), representative.conclusion())));
            }
            List<Candidate> claims = eligible.stream().map(FactPoint::claim).toList();
            int selected = -1;
            for (int c = 0; c < claims.size(); c++) {
                if (sameClaim(entry, claims.get(c))) {
                    selected = c;
                    break;
                }
            }
            if (selected < 0 && !claims.isEmpty()) selected = matcher.select(entry, List.copyOf(claims));
            if (selected < -1 || selected >= eligible.size()) throw new IOException("Invalid knowledge match index");
            FactPoint match = selected < 0 ? null : eligible.get(selected);
            Common.PointId id = match == null
                    ? Common.PointId.newBuilder().setUuid(UUID.randomUUID().toString()).build() : match.id();
            List<KnowledgeStore.KnowledgeSource> merged = match == null
                    ? new ArrayList<>() : new ArrayList<>(sources(match.payload()));
            merged.removeIf(s -> s.documentId() == documentId || s.url().equals(url));
            merged.add(incoming);
            // Keep old canonical text/vector together until its source is removed or expires.
            Map<String, Value> payload = payload(match == null ? Map.of() : match.payload(), merged, now);
            if (match == null) {
                await(client.upsertAsync(Points.UpsertPoints.newBuilder().setCollectionName(collection).setWait(true)
                        .addPoints(Points.PointStruct.newBuilder().setId(id)
                                .setVectors(namedVectors(Map.of(vectorName, vector(vectors.get(i)))))
                                .putAllPayload(payload)).build()));
            } else {
                await(client.setPayloadAsync(collection, payload, List.of(id), true, null, null));
            }
            current.put(id, payload);
            retained.add(id);
        }
        // Do not delete a shared point when just one document changes its statement.
        for (var point : previous) {
            if (!obsolete.contains(point.getId()) || retained.contains(point.getId())) continue;
            var latest = current.get(point.getId());
            var remaining = new ArrayList<>(sources(latest));
            remaining.removeIf(s -> s.documentId() == documentId);
            if (remaining.isEmpty()) await(client.deleteAsync(Points.DeletePoints.newBuilder()
                    .setCollectionName(collection).setWait(true).setPoints(Points.PointsSelector.newBuilder()
                            .setPoints(Points.PointsIdsList.newBuilder().addIds(point.getId()))).build()));
            else await(client.setPayloadAsync(collection, payload(latest, remaining, now), List.of(point.getId()), true, null, null));
        }
    }

    List<KnowledgeStore.KnowledgeSource> documentSources(long documentId) throws IOException {
        List<KnowledgeStore.KnowledgeSource> result = new ArrayList<>();
        for (var point : documentPoints(documentId)) {
            for (var source : sources(point.getPayloadMap())) {
                if (source.documentId() == documentId) result.add(source);
            }
        }
        return List.copyOf(result);
    }

    private static boolean sameClaim(KnowledgeQueryParser.Entry incoming, Candidate existing) {
        return incoming.question().equals(existing.question()) && incoming.conclusion().equals(existing.conclusion());
    }

    List<KnowledgeStore.KnowledgeSource> sources(Map<String, Value> payload) throws IOException {
        Value sourceList = payload.get("sources");
        if (sourceList != null && sourceList.hasListValue()) {
            List<KnowledgeStore.KnowledgeSource> result = new ArrayList<>();
            for (Value item : sourceList.getListValue().getValuesList()) {
                Map<String, Value> fields = item.getStructValue().getFieldsMap();
                long id = number(fields, "document_id");
                String url = string(fields, "url");
                if (id <= 0 || url.isBlank()) throw new IOException("Invalid knowledge source payload");
                result.add(new KnowledgeStore.KnowledgeSource(id, url, string(fields, "title"),
                        string(fields, "query"), string(fields, "conclusion"),
                        (float) fields.getOrDefault("confidence", Value.getDefaultInstance()).getDoubleValue(),
                        number(fields, "ttl")));
            }
            return result;
        }
        long id = number(payload, "document_id");
        if (id <= 0) throw new IOException("Missing legacy knowledge document id");
        var document = documents.apply(id);
        if (document == null || document.url() == null || document.url().isBlank())
            throw new IOException("Cannot resolve legacy knowledge source: " + id);
        return List.of(new KnowledgeStore.KnowledgeSource(id, document.url(), document.title(),
                string(payload, "query"), string(payload, "conclusion"),
                (float) payload.getOrDefault("confidence", Value.getDefaultInstance()).getDoubleValue(), number(payload, "ttl")));
    }

    List<KnowledgeStore.KnowledgeSource> activeSources(Map<String, Value> payload, long now) throws IOException {
        return sources(payload).stream().filter(s -> s.expiresAt() >= now).toList();
    }

    private List<Points.RetrievedPoint> documentPoints(long documentId) throws IOException {
        var match = Common.Match.newBuilder().setInteger(documentId);
        Common.Filter filter = Common.Filter.newBuilder()
                .addShould(Common.Condition.newBuilder().setField(Common.FieldCondition.newBuilder().setKey("document_id").setMatch(match)))
                .addShould(Common.Condition.newBuilder().setField(Common.FieldCondition.newBuilder().setKey("document_ids").setMatch(match)))
                .build();
        List<Points.RetrievedPoint> result = new ArrayList<>();
        Common.PointId offset = null;
        do {
            var request = Points.ScrollPoints.newBuilder().setCollectionName(collection).setFilter(filter).setLimit(128)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true));
            if (offset != null) request.setOffset(offset);
            var page = await(client.scrollAsync(request.build()));
            result.addAll(page.getResultList());
            offset = page.hasNextPageOffset() ? page.getNextPageOffset() : null;
        } while (offset != null);
        return result;
    }

    private static Map<String, Value> payload(Map<String, Value> old, List<KnowledgeStore.KnowledgeSource> sources, long now) {
        Map<String, Value> result = new HashMap<>(old);
        var representative = sources.stream().filter(s -> s.expiresAt() >= now).findFirst().orElse(sources.get(0));
        result.put("document_id", integer(representative.documentId())); // legacy readers
        result.put("query", text(representative.question()));
        result.put("conclusion", text(representative.conclusion()));
        result.put("confidence", decimal(representative.confidence()));
        result.put("ttl", integer(sources.stream().mapToLong(KnowledgeStore.KnowledgeSource::expiresAt).max().orElseThrow()));
        result.putIfAbsent("created_at", integer(now));
        result.put("updated_at", integer(now));
        var ids = io.qdrant.client.grpc.JsonWithInt.ListValue.newBuilder();
        sources.stream().map(KnowledgeStore.KnowledgeSource::documentId).distinct().forEach(id -> ids.addValues(integer(id)));
        result.put("document_ids", Value.newBuilder().setListValue(ids).build());
        var urls = io.qdrant.client.grpc.JsonWithInt.ListValue.newBuilder();
        sources.stream().map(KnowledgeStore.KnowledgeSource::url).distinct().forEach(url -> urls.addValues(text(url)));
        result.put("source_urls", Value.newBuilder().setListValue(urls).build());
        var list = io.qdrant.client.grpc.JsonWithInt.ListValue.newBuilder();
        for (var source : sources) {
            var fields = io.qdrant.client.grpc.JsonWithInt.Struct.newBuilder()
                    .putFields("document_id", integer(source.documentId())).putFields("url", text(source.url()))
                    .putFields("title", text(source.title())).putFields("query", text(source.question()))
                    .putFields("conclusion", text(source.conclusion())).putFields("confidence", decimal(source.confidence()))
                    .putFields("ttl", integer(source.expiresAt()));
            list.addValues(Value.newBuilder().setStructValue(fields));
        }
        result.put("sources", Value.newBuilder().setListValue(list).build());
        return result;
    }

    private static Common.Condition range(String key, long minimum) {
        return Common.Condition.newBuilder().setField(Common.FieldCondition.newBuilder().setKey(key)
                .setRange(Common.Range.newBuilder().setGte(minimum))).build();
    }

    private static Value text(String value) { return Value.newBuilder().setStringValue(Objects.toString(value, "")).build(); }
    private static Value integer(long value) { return Value.newBuilder().setIntegerValue(value).build(); }
    private static Value decimal(double value) { return Value.newBuilder().setDoubleValue(value).build(); }
    private static String string(Map<String, Value> payload, String key) { return payload.getOrDefault(key, Value.getDefaultInstance()).getStringValue(); }
    private static long number(Map<String, Value> payload, String key) { return payload.getOrDefault(key, Value.getDefaultInstance()).getIntegerValue(); }

    private static <T> T await(Future<T> future) throws IOException {
        try { return future.get(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
        catch (ExecutionException error) { throw new IOException(error.getCause()); }
    }
}
