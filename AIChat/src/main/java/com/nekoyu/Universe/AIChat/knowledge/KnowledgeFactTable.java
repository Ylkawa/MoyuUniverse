package com.nekoyu.Universe.AIChat.knowledge;

import com.google.gson.*;
import com.nekoyu.Universe.AIChat.PgVectorTable;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Typed fact rows. Only source-specific claims are stored as a JSON array. */
public class KnowledgeFactTable {
    public record Source(long documentId, String question, String conclusion, float confidence, Instant reviewDueAt) {
        public Source {
            if (documentId <= 0 || question == null || question.isBlank() || conclusion == null || conclusion.isBlank()
                    || !Float.isFinite(confidence) || confidence < 0 || confidence > 1 || reviewDueAt == null)
                throw new IllegalArgumentException("Invalid fact source");
        }
    }
    public record Fact(UUID id, String question, String conclusion, float confidence, Instant reviewDueAt,
                       Instant createdAt, Instant updatedAt, List<Source> sources) {
        public Fact { sources = List.copyOf(sources); }
        public List<Source> activeSources(Instant now) {
            return sources.stream().filter(s -> !s.reviewDueAt().isBefore(now)).toList();
        }
    }
    public record Match(Fact fact, double score) {}

    private static final String COLUMNS = "id, question, conclusion, confidence, review_due_at, created_at, updated_at, sources";
    private final DataSource dataSource;
    private final String table;
    private final int dimension;

    public KnowledgeFactTable(DataSource dataSource, String table, int dimension) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.table = PgVectorTable.sqlName(table);
        if (dimension < 1 || dimension > 2000) throw new IllegalArgumentException("Vector dimension must be 1..2000");
        this.dimension = dimension;
    }

    public void initialize() throws IOException {
        try (Connection c = dataSource.getConnection()) { initialize(c, table, dimension); }
        catch (SQLException e) { throw new IOException("Cannot initialize facts: " + table, e); }
    }

    public static void initialize(Connection c, String table, int dimension) throws SQLException {
        PgVectorTable.sqlName(table);
        if (dimension < 1 || dimension > 2000) throw new IllegalArgumentException("Vector dimension must be 1..2000");
        String name = table.substring(table.indexOf('.') + 1);
        try (Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS vector");
            s.execute("CREATE SCHEMA IF NOT EXISTS " + table.split("\\.")[0]);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        id uuid PRIMARY KEY,
                        question text NOT NULL CHECK (btrim(question) <> ''),
                        conclusion text NOT NULL CHECK (btrim(conclusion) <> ''),
                        embedding vector(%d) NOT NULL,
                        confidence real NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
                        review_due_at timestamptz NOT NULL,
                        created_at timestamptz NOT NULL DEFAULT now(),
                        updated_at timestamptz NOT NULL DEFAULT now(),
                        sources jsonb NOT NULL CHECK (jsonb_typeof(sources) = 'array'
                            AND jsonb_array_length(sources) > 0)
                    )
                    """.formatted(table, dimension));
            s.execute("CREATE INDEX IF NOT EXISTS " + name + "_embedding_idx ON " + table + " USING hnsw (embedding vector_cosine_ops)");
            s.execute("CREATE INDEX IF NOT EXISTS " + name + "_review_due_at_idx ON " + table + " (review_due_at)");
            s.execute("CREATE INDEX IF NOT EXISTS " + name + "_sources_idx ON " + table + " USING gin (sources jsonb_path_ops)");
        }
        PgVectorTable.requireDimension(c, table, dimension);
    }

    public List<Match> search(List<Float> vector, Instant now, int limit, double minimum) throws IOException {
        String literal = PgVectorTable.vector(vector, dimension);
        return select("SELECT " + COLUMNS + ", 1 - (embedding <=> ?::vector) AS score FROM " + table
                        + " WHERE review_due_at >= ? AND (embedding <=> ?::vector) <= ?"
                        + " ORDER BY embedding <=> ?::vector, id LIMIT ?",
                List.of(literal, now.atOffset(ZoneOffset.UTC), literal, 1 - minimum, literal, limit));
    }

    public List<Fact> forDocument(long documentId) throws IOException {
        return select("SELECT " + COLUMNS + ", 0 AS score FROM " + table + " WHERE sources @> ?::jsonb ORDER BY id",
                List.of("[{\"document_id\":" + documentId + "}]")).stream().map(Match::fact).toList();
    }

    private List<Match> select(String sql, List<?> parameters) throws IOException {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) s.setObject(i + 1, parameters.get(i));
            List<Match> result = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) result.add(new Match(new Fact(r.getObject("id", UUID.class), r.getString("question"),
                        r.getString("conclusion"), r.getFloat("confidence"), r.getTimestamp("review_due_at").toInstant(),
                        r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant(),
                        parseSources(r.getString("sources"))), r.getDouble("score")));
            }
            return result;
        } catch (SQLException | IllegalArgumentException e) { throw new IOException("Fact query failed: " + table, e); }
    }

    public void insert(Fact fact, List<Float> embedding) throws IOException {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement("INSERT INTO " + table
                + " (" + COLUMNS + ", embedding) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector)")) {
            bindFact(s, fact);
            s.setString(9, PgVectorTable.vector(embedding, dimension));
            s.executeUpdate();
        } catch (SQLException e) { throw new IOException("Fact insert failed: " + table, e); }
    }

    /** Updating support never changes the canonical question or its embedding. */
    public void updateSupport(Fact fact) throws IOException {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE " + table
                + " SET conclusion=?, confidence=?, review_due_at=?, updated_at=?, sources=?::jsonb WHERE id=?")) {
            s.setString(1, fact.conclusion());
            s.setFloat(2, fact.confidence());
            s.setObject(3, fact.reviewDueAt().atOffset(ZoneOffset.UTC));
            s.setObject(4, fact.updatedAt().atOffset(ZoneOffset.UTC));
            s.setString(5, sourcesJson(fact.sources()));
            s.setObject(6, fact.id());
            if (s.executeUpdate() != 1) throw new SQLException("Fact no longer exists: " + fact.id());
        } catch (SQLException e) { throw new IOException("Fact support update failed: " + table, e); }
    }

    public void delete(UUID id) throws IOException {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM " + table + " WHERE id=?")) {
            s.setObject(1, id);
            s.executeUpdate();
        } catch (SQLException e) { throw new IOException("Fact delete failed: " + table, e); }
    }

    private static void bindFact(PreparedStatement s, Fact fact) throws SQLException {
        s.setObject(1, fact.id());
        s.setString(2, fact.question());
        s.setString(3, fact.conclusion());
        s.setFloat(4, fact.confidence());
        s.setObject(5, fact.reviewDueAt().atOffset(ZoneOffset.UTC));
        s.setObject(6, fact.createdAt().atOffset(ZoneOffset.UTC));
        s.setObject(7, fact.updatedAt().atOffset(ZoneOffset.UTC));
        s.setString(8, sourcesJson(fact.sources()));
    }

    public static String sourcesJson(List<Source> sources) {
        JsonArray result = new JsonArray();
        for (Source source : sources) {
            JsonObject fields = new JsonObject();
            fields.addProperty("document_id", source.documentId());
            fields.addProperty("question", source.question());
            fields.addProperty("conclusion", source.conclusion());
            fields.addProperty("confidence", source.confidence());
            fields.addProperty("review_due_at", source.reviewDueAt().toString());
            result.add(fields);
        }
        return result.toString();
    }

    public static List<Source> parseSources(String json) {
        List<Source> result = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject fields = element.getAsJsonObject();
            result.add(new Source(fields.get("document_id").getAsLong(), fields.get("question").getAsString(),
                    fields.get("conclusion").getAsString(), fields.get("confidence").getAsFloat(),
                    Instant.parse(fields.get("review_due_at").getAsString())));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Fact requires at least one source");
        return List.copyOf(result);
    }
}
