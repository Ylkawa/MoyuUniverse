package com.nekoyu.Universe.AIChat.knowledge;

import java.io.IOException;
import com.google.gson.*;
import com.nekoyu.Universe.AIChat.PgVectorTable;
import java.util.*;
import java.util.function.LongFunction;

/** One PostgreSQL row per fact; each source retains its own claim, support and expiry. */
final class KnowledgeFactIndex {
    record Candidate(String question, String conclusion) {}
    @FunctionalInterface interface Matcher {
        int select(KnowledgeQueryParser.Entry incoming, List<Candidate> candidates) throws IOException;
    }
    @FunctionalInterface interface ConflictChecker {
        boolean conflicts(KnowledgeQueryParser.Entry incoming, Candidate ownOld) throws IOException;
    }

    private record FactPoint(UUID id, JsonObject payload, Candidate claim) {}

    private final PgVectorTable table;

    private final int candidateLimit;
    private final LongFunction<KnowledgeStore.KnowledgeSource> documents;
    private final Matcher matcher;

    KnowledgeFactIndex(PgVectorTable table, int candidateLimit,
                       LongFunction<KnowledgeStore.KnowledgeSource> documents, Matcher matcher) {
        this.table = table;

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
        List<PgVectorTable.Point> previous = documentPoints(documentId);
        Set<UUID> obsolete = new HashSet<>();
        Map<UUID, JsonObject> current = new LinkedHashMap<>();
        for (var point : previous) {
            current.put(point.id(), point.payload());
            if (conflictChecker == null) {
                obsolete.add(point.id());
                continue;
            }
            // Decide every removal before writing anything. An unavailable classifier cannot remove support.
            for (var source : sources(point.payload())) {
                if (source.documentId() != documentId) continue;
                var ownOld = new Candidate(source.question(), source.conclusion());
                for (var incoming : entries) {
                    if (sameClaim(incoming, ownOld)) continue;
                    if (conflictChecker.conflicts(incoming, ownOld)) obsolete.add(point.id());
                }
            }
        }
        Set<UUID> retained = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            var incoming = new KnowledgeStore.KnowledgeSource(documentId, url, title, entry.question(),
                    entry.conclusion(), entry.confidence(), now + entry.ttlDays() * 86_400_000L);
            List<FactPoint> eligible = new ArrayList<>();
            Set<UUID> seen = new HashSet<>();
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
            List<PgVectorTable.Point> candidates = table.search(vectors.get(i),
                    "(payload->>'ttl')::bigint >= ?", List.of(now), candidateLimit, 0.6);
            for (var candidate : candidates) {
                if (!seen.add(candidate.id())) continue;
                var active = activeSources(candidate.payload(), now);
                if (active.isEmpty()) continue;
                // The current live representative, rather than an expired source, determines equivalence.
                var representative = active.get(0);
                eligible.add(new FactPoint(candidate.id(), candidate.payload(),
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
            UUID id = match == null
                    ? UUID.randomUUID() : match.id();
            List<KnowledgeStore.KnowledgeSource> merged = match == null
                    ? new ArrayList<>() : new ArrayList<>(sources(match.payload()));
            merged.removeIf(s -> s.documentId() == documentId || s.url().equals(url));
            merged.add(incoming);
            // Keep old canonical text/vector together until its source is removed or expires.
            JsonObject payload = payload(match == null ? new JsonObject() : match.payload(), merged, now);
            if (match == null) table.upsert(id, vectors.get(i), payload);
            else table.payload(id, payload);
            current.put(id, payload);
            retained.add(id);
        }
        // Do not delete a shared point when just one document changes its statement.
        for (var point : previous) {
            if (!obsolete.contains(point.id()) || retained.contains(point.id())) continue;
            var latest = current.get(point.id());
            var remaining = new ArrayList<>(sources(latest));
            remaining.removeIf(s -> s.documentId() == documentId);
            if (remaining.isEmpty()) table.delete(point.id());
            else table.payload(point.id(), payload(latest, remaining, now));
        }
    }

    List<KnowledgeStore.KnowledgeSource> documentSources(long documentId) throws IOException {
        List<KnowledgeStore.KnowledgeSource> result = new ArrayList<>();
        for (var point : documentPoints(documentId)) {
            for (var source : sources(point.payload())) {
                if (source.documentId() == documentId) result.add(source);
            }
        }
        return List.copyOf(result);
    }

    private static boolean sameClaim(KnowledgeQueryParser.Entry incoming, Candidate existing) {
        return incoming.question().equals(existing.question()) && incoming.conclusion().equals(existing.conclusion());
    }

    List<KnowledgeStore.KnowledgeSource> sources(JsonObject payload) throws IOException {
        if (payload.has("sources") && payload.get("sources").isJsonArray()) {
            List<KnowledgeStore.KnowledgeSource> result = new ArrayList<>();
            for (JsonElement item : payload.getAsJsonArray("sources")) {
                JsonObject fields = item.getAsJsonObject();
                long id = number(fields, "document_id");
                String url = string(fields, "url");
                if (id <= 0 || url.isBlank()) throw new IOException("Invalid knowledge source payload");
                result.add(new KnowledgeStore.KnowledgeSource(id, url, string(fields, "title"),
                        string(fields, "query"), string(fields, "conclusion"),
                        decimal(fields, "confidence"), number(fields, "ttl")));
            }
            return result;
        }
        long id = number(payload, "document_id");
        if (id <= 0) throw new IOException("Missing legacy knowledge document id");
        var document = documents.apply(id);
        if (document == null || document.url() == null || document.url().isBlank())
            throw new IOException("Cannot resolve legacy knowledge source: " + id);
        return List.of(new KnowledgeStore.KnowledgeSource(id, document.url(), document.title(),
                string(payload, "query"), string(payload, "conclusion"), decimal(payload, "confidence"), number(payload, "ttl")));
    }

    List<KnowledgeStore.KnowledgeSource> activeSources(JsonObject payload, long now) throws IOException {
        return sources(payload).stream().filter(s -> s.expiresAt() >= now).toList();
    }

    private List<PgVectorTable.Point> documentPoints(long documentId) throws IOException {
        return table.list("(payload->>'document_id')::bigint = ? OR payload @> ?::jsonb",
                List.of(documentId, "{\"document_ids\":[" + documentId + "]}"), "id", Integer.MAX_VALUE);
    }

    private static JsonObject payload(JsonObject old, List<KnowledgeStore.KnowledgeSource> sources, long now) {
        JsonObject result = old.deepCopy();
        var representative = sources.stream().filter(s -> s.expiresAt() >= now).findFirst().orElse(sources.get(0));
        result.addProperty("document_id", representative.documentId());
        result.addProperty("query", representative.question());
        result.addProperty("conclusion", representative.conclusion());
        result.addProperty("confidence", representative.confidence());
        result.addProperty("ttl", sources.stream().mapToLong(KnowledgeStore.KnowledgeSource::expiresAt).max().orElseThrow());
        if (!result.has("created_at")) result.addProperty("created_at", now);
        result.addProperty("updated_at", now);
        JsonArray ids = new JsonArray(), urls = new JsonArray(), list = new JsonArray();
        sources.stream().map(KnowledgeStore.KnowledgeSource::documentId).distinct().forEach(ids::add);
        sources.stream().map(KnowledgeStore.KnowledgeSource::url).distinct().forEach(urls::add);
        result.add("document_ids", ids);
        result.add("source_urls", urls);
        for (var source : sources) {
            JsonObject fields = new JsonObject();
            fields.addProperty("document_id", source.documentId());
            fields.addProperty("url", source.url());
            fields.addProperty("title", source.title());
            fields.addProperty("query", source.question());
            fields.addProperty("conclusion", source.conclusion());
            fields.addProperty("confidence", source.confidence());
            fields.addProperty("ttl", source.expiresAt());
            list.add(fields);
        }
        result.add("sources", list);
        return result;
    }

    private static String string(JsonObject payload, String key) {
        return payload.has(key) && !payload.get(key).isJsonNull() ? payload.get(key).getAsString() : "";
    }
    private static long number(JsonObject payload, String key) {
        return payload.has(key) && !payload.get(key).isJsonNull() ? payload.get(key).getAsLong() : 0;
    }
    private static float decimal(JsonObject payload, String key) {
        return payload.has(key) && !payload.get(key).isJsonNull() ? payload.get(key).getAsFloat() : 0;
    }
}
