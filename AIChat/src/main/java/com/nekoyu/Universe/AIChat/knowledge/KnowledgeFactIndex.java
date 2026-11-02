package com.nekoyu.Universe.AIChat.knowledge;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.nekoyu.Universe.AIChat.knowledge.KnowledgeFactTable.*;

/** One row per fact; each source retains its own claim, support and review deadline. */
final class KnowledgeFactIndex {
    record Candidate(String question, String conclusion) {}
    @FunctionalInterface interface Matcher {
        int select(KnowledgeQueryParser.Entry incoming, List<Candidate> candidates) throws IOException;
    }
    @FunctionalInterface interface ConflictChecker {
        boolean conflicts(KnowledgeQueryParser.Entry incoming, Candidate ownOld) throws IOException;
    }
    private record FactCandidate(Fact fact, Candidate claim) {}
    private final KnowledgeFactTable table;
    private final int candidateLimit;
    private final Matcher matcher;

    KnowledgeFactIndex(KnowledgeFactTable table, int candidateLimit, Matcher matcher) {
        this.table = table;
        this.candidateLimit = Math.max(1, Math.min(10, candidateLimit));
        this.matcher = matcher;
    }

    /** Serialized by the store; failed writes never trigger source removal. */
    void replace(long documentId, List<KnowledgeQueryParser.Entry> entries,
                 List<List<Float>> vectors, Instant now) throws IOException {
        update(documentId, entries, vectors, now, null);
    }

    /** A fragment updates only claims it explicitly contradicts, leaving unrelated support intact. */
    void merge(long documentId, List<KnowledgeQueryParser.Entry> entries,
               List<List<Float>> vectors, Instant now, ConflictChecker conflictChecker) throws IOException {
        update(documentId, entries, vectors, now, Objects.requireNonNull(conflictChecker));
    }

    private void update(long documentId, List<KnowledgeQueryParser.Entry> entries,
                        List<List<Float>> vectors, Instant now, ConflictChecker conflictChecker) throws IOException {
        if (entries.size() != vectors.size()) throw new IOException("Fact embedding count mismatch");
        List<Fact> previous = table.forDocument(documentId);
        Set<UUID> obsolete = new HashSet<>();
        Map<UUID, Fact> current = new LinkedHashMap<>();
        for (Fact fact : previous) {
            current.put(fact.id(), fact);
            if (conflictChecker == null) {
                obsolete.add(fact.id());
                continue;
            }
            // Decide every removal before writing. An unavailable classifier cannot remove support.
            for (Source source : fact.sources()) {
                if (source.documentId() != documentId) continue;
                Candidate ownOld = new Candidate(source.question(), source.conclusion());
                for (var incoming : entries) {
                    if (!sameClaim(incoming, ownOld) && conflictChecker.conflicts(incoming, ownOld)) obsolete.add(fact.id());
                }
            }
        }
        Set<UUID> retained = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            Source incoming = new Source(documentId, entry.question(), entry.conclusion(), entry.confidence(),
                    now.plus(entry.ttlDays(), ChronoUnit.DAYS));
            List<FactCandidate> eligible = new ArrayList<>();
            Set<UUID> seen = new HashSet<>();
            // A source can renew its own expired fact, including facts written earlier in this batch.
            for (Fact fact : current.values()) {
                for (Source source : fact.sources()) {
                    if (source.documentId() != documentId) continue;
                    eligible.add(new FactCandidate(fact, new Candidate(source.question(), source.conclusion())));
                    seen.add(fact.id());
                    break;
                }
            }
            for (Match candidate : table.search(vectors.get(i), now, candidateLimit, 0.6)) {
                Fact fact = candidate.fact();
                if (!seen.add(fact.id())) continue;
                List<Source> active = fact.activeSources(now);
                if (active.isEmpty()) continue;
                Source representative = active.get(0);
                eligible.add(new FactCandidate(fact, new Candidate(representative.question(), representative.conclusion())));
            }
            List<Candidate> claims = eligible.stream().map(FactCandidate::claim).toList();
            int selected = -1;
            for (int c = 0; c < claims.size(); c++) {
                if (sameClaim(entry, claims.get(c))) { selected = c; break; }
            }
            if (selected < 0 && !claims.isEmpty()) selected = matcher.select(entry, claims);
            if (selected < -1 || selected >= eligible.size()) throw new IOException("Invalid knowledge match index");
            Fact match = selected < 0 ? null : eligible.get(selected).fact();
            List<Source> merged = match == null ? new ArrayList<>() : new ArrayList<>(match.sources());
            merged.removeIf(s -> s.documentId() == documentId);
            merged.add(incoming);
            Fact fact = withSources(match, merged, now);
            if (match == null) table.insert(fact, vectors.get(i));
            else table.updateSupport(fact);
            current.put(fact.id(), fact);
            retained.add(fact.id());
        }
        for (Fact fact : previous) {
            if (!obsolete.contains(fact.id()) || retained.contains(fact.id())) continue;
            Fact latest = current.get(fact.id());
            List<Source> remaining = new ArrayList<>(latest.sources());
            remaining.removeIf(s -> s.documentId() == documentId);
            if (remaining.isEmpty()) table.delete(fact.id());
            else table.updateSupport(withSources(latest, remaining, now));
        }
    }

    List<Source> documentSources(long documentId) throws IOException {
        return table.forDocument(documentId).stream().flatMap(f -> f.sources().stream())
                .filter(s -> s.documentId() == documentId).toList();
    }

    private static boolean sameClaim(KnowledgeQueryParser.Entry incoming, Candidate existing) {
        return incoming.question().equals(existing.question()) && incoming.conclusion().equals(existing.conclusion());
    }

    private static Fact withSources(Fact old, List<Source> sources, Instant now) {
        Source representative = sources.stream().filter(s -> !s.reviewDueAt().isBefore(now)).findFirst().orElse(sources.get(0));
        Instant due = sources.stream().map(Source::reviewDueAt).max(Comparator.naturalOrder()).orElseThrow();
        return new Fact(old == null ? UUID.randomUUID() : old.id(), old == null ? representative.question() : old.question(),
                representative.conclusion(), representative.confidence(), due, old == null ? now : old.createdAt(), now, sources);
    }
}
