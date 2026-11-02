package com.nekoyu.Universe.AIChat.knowledge;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** Storage boundary for testing fact classification independently of JDBC and providers. */
class FactTableFixture extends KnowledgeFactTable {
    record Stored(Fact fact, List<Float> embedding) {
        UUID id() { return fact.id(); }
    }
    final Map<UUID, Stored> points = new LinkedHashMap<>();
    long lastDocumentId;
    FactTableFixture() { super(new HikariDataSource(), "test.facts", 2); }
    protected void beforeWrite() throws IOException {}
    @Override public void initialize() {}
    @Override public List<Match> search(List<Float> vector, Instant now, int limit, double minimum) {
        return points.values().stream().filter(p -> !p.fact().reviewDueAt().isBefore(now)).limit(limit)
                .map(p -> new Match(p.fact(), 0.9)).toList();
    }
    @Override public List<Fact> forDocument(long documentId) {
        lastDocumentId = documentId;
        return points.values().stream().map(Stored::fact)
                .filter(f -> f.sources().stream().anyMatch(s -> s.documentId() == documentId)).toList();
    }
    @Override public void insert(Fact fact, List<Float> embedding) throws IOException {
        beforeWrite();
        points.put(fact.id(), new Stored(fact, List.copyOf(embedding)));
    }
    @Override public void updateSupport(Fact fact) throws IOException {
        beforeWrite();
        Stored old = points.get(fact.id());
        if (old == null) throw new IOException("Missing fact");
        points.put(fact.id(), new Stored(fact, old.embedding()));
    }
    @Override public void delete(UUID id) throws IOException { beforeWrite(); points.remove(id); }
}
