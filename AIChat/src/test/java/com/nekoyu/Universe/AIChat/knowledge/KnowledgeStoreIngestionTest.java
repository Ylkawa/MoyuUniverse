package com.nekoyu.Universe.AIChat.knowledge;

import com.google.gson.Gson;
import com.nekoyu.Universe.AIChat.Config;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BiPredicate;

import static com.nekoyu.Universe.AIChat.knowledge.LibrarianResearchTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production ingestion queue and indexing flow with offline JDBC/gRPC/model dependencies. */
class KnowledgeStoreIngestionTest {
    private static final String URL = "https://source1.invalid/article";

    @Test void failedEmbeddingCanBeRetriedByResearchingTheSameArchivedSource() throws Exception {
        Fixture f = new Fixture();
        f.failEmbedding = true;
        assertThrows(IOException.class, () -> f.learn(fact("A price?", "A costs 10")));
        assertEquals(1, f.sql.rows.size(), "keep the source archive even if embedding is unavailable");
        assertTrue(f.vectors.points.isEmpty());
        f.failEmbedding = false;
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> f.learn(fact("A price?", "A costs 10")));
        assertEquals(1, f.vectors.points.size());
        assertEquals(2, f.embeddingCalls);
        assertTrue(f.sql.content().contains("A costs 10"));
    }

    @Test void supplementaryResearchAndScopedCorrectionPreserveOtherFactsInBothStores() throws Exception {
        Fixture f = new Fixture();
        f.learn(fact("A price?", "A costs 10"), fact("B version?", "B version is 2"));
        f.learn(fact("C platform?", "C runs on Linux"));
        f.conflict = (old, incoming) -> old.contains("A costs 10") && incoming.contains("A costs 20");
        f.learn(fact("A price?", "A costs 20"));
        assertEquals(Set.of("A costs 20", "B version is 2", "C runs on Linux"), f.conclusions());
        assertTrue(f.sql.content().contains("B version is 2"), "archive must retain the undisputed source statement");
        assertTrue(f.sql.content().contains("C runs on Linux"));
        assertFalse(f.sql.content().contains("A costs 10"));
        assertEquals(1, f.sql.rows.size(), "all observations belong to the same URL document");
    }

    @Test void conflictOrParsingFailureLeavesPreviouslyIndexedSupportAndArchiveIntact() throws Exception {
        Fixture f = new Fixture();
        f.learn(fact("A price?", "A costs 10"), fact("B version?", "B version is 2"));
        String before = f.sql.content();
        f.failConflict = true;
        assertThrows(IOException.class, () -> f.learn(fact("A price?", "A costs 20")));
        assertEquals(Set.of("A costs 10", "B version is 2"), f.conclusions());
        assertEquals(before, f.sql.content());
        f.failConflict = false;
        f.knowledgeOutput = "{\"items\":[{\"question\":\"invalid missing fields\"}]}";
        assertThrows(IOException.class, f::submit);
        assertEquals(before, f.sql.content());
        assertEquals(2, f.vectors.points.size());
        f.knowledgeOutput = "{\"items\":[]}";
        assertDoesNotThrow(f::submit);
        assertEquals(before, f.sql.content(), "explicit empty extraction is a successful non-destructive no-op");
        assertEquals(2, f.vectors.points.size());
    }

    @Test void sqlAndEmbeddingCountFailuresPropagateInsteadOfReturningSuccess() throws Exception {
        Fixture f = new Fixture();
        f.sql.fail = true;
        assertThrows(IOException.class, () -> f.store.indexDocumentSync(URL, "title", "source"));
        f.sql.fail = false;
        f.emptyEmbedding = true;
        assertThrows(IOException.class, () -> f.learn(fact("A price?", "A costs 10")));
        assertTrue(f.vectors.points.isEmpty());
    }

    private static Map<String, Object> fact(String question, String conclusion) {
        return Map.of("question", question, "conclusion", conclusion, "confidence", .95, "ttl_days", 30);
    }

    private static class Fixture {
        final KnowledgeFactIndexTest.Fixture vectors = new KnowledgeFactIndexTest.Fixture();
        final Sql sql = new Sql();
        final KnowledgeStore store;
        boolean failEmbedding, emptyEmbedding, failConflict;
        int embeddingCalls;
        String knowledgeOutput;
        BiPredicate<String, String> conflict = (old, incoming) -> false;

        Fixture() {
            var cfg = new Config.KnowledgeStore();
            cfg.dimension = 2;
            store = new KnowledgeStore(cfg, sql, vectors.table, request -> {
                embeddingCalls++;
                if (failEmbedding) throw new IOException("offline embedding failure");
                var result = new EmbeddingResponse();
                if (!emptyEmbedding) for (var ignored : request.message) {
                    var data = new EmbeddingResponse.Embedding();
                    data.embedding = new double[]{.3, .7};
                    result.data.add(data);
                }
                return result;
            }, provider((messages, request) -> {
                String schema = request.responseFormat.jsonSchema.name;
                return switch (schema) {
                    case "knowledge_sources" -> response(new KnowledgeSources(List.of(
                            new KnowledgeSources.Source(URL, "source", "partial research observation"))).toJson(), "stop");
                    case "knowledge_items" -> response(knowledgeOutput, "stop");
                    case "knowledge_fact_match" -> response("{\"match_index\":-1}", "stop");
                    case "knowledge_conflict" -> {
                        if (failConflict) throw new IOException("offline conflict classifier failure");
                        boolean value = conflict.test(messages.get(1).toString(), messages.get(2).toString());
                        yield response("{\"conflict\":" + value + "}", "stop");
                    }
                    default -> throw new AssertionError(schema);
                };
            }));
        }
        @SafeVarargs final void learn(Map<String, Object>... facts) throws Exception {
            knowledgeOutput = new Gson().toJson(Map.of("items", List.of(facts)));
            submit();
        }
        void submit() throws Exception {
            store.ingestResearch("question", "report " + URL, Map.of(URL, "observed evidence"));
            store.processNextIndexTask();
        }
        Set<String> conclusions() {
            Set<String> result = new HashSet<>();
            vectors.points.values().forEach(p -> result.add(p.payload().get("conclusion").getAsString()));
            return result;
        }
    }

    private static class Sql extends HikariDataSource {
        final Map<String, Row> rows = new LinkedHashMap<>();
        boolean fail;
        String content() { return rows.values().iterator().next().content; }
        @Override public Connection getConnection() throws SQLException {
            if (fail) throw new SQLException("offline SQL failure");
            return proxy(Connection.class, (o,m,a) -> switch (m.getName()) {
                case "prepareStatement" -> statement((String)a[0]);
                case "close" -> null;
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
        PreparedStatement statement(String sql) {
            Map<Integer,Object> parameters = new HashMap<>();
            return proxy(PreparedStatement.class, (o,m,a) -> switch (m.getName()) {
                case "setString", "setLong", "setObject" -> { parameters.put((Integer)a[0], a[1]); yield null; }
                case "executeQuery" -> {
                    Row row;
                    if (sql.contains("INSERT INTO")) {
                        String hash = (String)parameters.get(2);
                        row = rows.computeIfAbsent(hash, k -> new Row(rows.size() + 1));
                        row.content = (String)parameters.get(4);
                    } else if (sql.contains("WHERE url_hash")) row = rows.get(parameters.get(1));
                    else row = rows.values().stream().filter(r -> Objects.equals(r.id, parameters.get(1))).findFirst().orElse(null);
                    Row selected = row;
                    boolean[] read = {false};
                    yield proxy(ResultSet.class, (r,rm,ra) -> switch (rm.getName()) {
                        case "next" -> { boolean found = !read[0] && selected != null; read[0] = true; yield found; }
                        case "getLong" -> selected.id;
                        case "getString" -> selected.content;
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(rm.getName());
                    });
                }
                case "close" -> null;
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }
    private static class Row {
        final long id;
        String content;
        Row(long id) { this.id = id; }
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
