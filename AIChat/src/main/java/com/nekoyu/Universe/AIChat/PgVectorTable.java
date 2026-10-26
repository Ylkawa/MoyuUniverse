package com.nekoyu.Universe.AIChat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.util.*;

/** Shared JDBC storage for memory and knowledge vectors. No additional client or connection pool. */
public class PgVectorTable {
    public record Point(UUID id, JsonObject payload, double score) {}
    private final DataSource dataSource;
    private final String table;
    private final int dimension;

    public PgVectorTable(DataSource dataSource, String table, int dimension) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.table = sqlName(table);
        if (dimension < 1 || dimension > 2000) throw new IllegalArgumentException("Vector dimension must be 1..2000");
        this.dimension = dimension;
    }

    public static String sqlName(String name) {
        if (name == null || !name.matches("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*"))
            throw new IllegalArgumentException("Expected schema.table: " + name);
        return name;
    }

    public void initialize() throws IOException {
        try (Connection c = dataSource.getConnection()) { initialize(c, table, dimension); }
        catch (SQLException e) { throw new IOException("Cannot initialize " + table, e); }
    }

    public static void initialize(Connection c, String table, int dimension) throws SQLException {
        sqlName(table);
        if (dimension < 1 || dimension > 2000) throw new IllegalArgumentException("Vector dimension must be 1..2000");
        try (Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS vector");
            s.execute("CREATE SCHEMA IF NOT EXISTS " + table.split("\\.")[0]);
            s.execute("CREATE TABLE IF NOT EXISTS " + table + " (id uuid PRIMARY KEY, embedding vector(" + dimension
                    + ") NOT NULL, payload jsonb NOT NULL DEFAULT '{}'::jsonb)");
            s.execute("CREATE INDEX IF NOT EXISTS " + index(table, "embedding") + " ON " + table
                    + " USING hnsw (embedding vector_cosine_ops)");
            s.execute("CREATE INDEX IF NOT EXISTS " + index(table, "payload") + " ON " + table + " USING gin (payload)");
        }
        requireDimension(c, table, dimension);
    }

    public static void requireDimension(Connection c, String table, int dimension) throws SQLException {
        sqlName(table);
        try (PreparedStatement s = c.prepareStatement("SELECT a.atttypmod FROM pg_attribute a WHERE a.attrelid = ?::regclass AND a.attname = 'embedding'")) {
            s.setString(1, table);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next() || r.getInt(1) != dimension) throw new SQLException("Vector dimension mismatch in " + table);
            }
        }
    }

    private static String index(String table, String suffix) { return table.substring(table.indexOf('.') + 1) + "_" + suffix + "_idx"; }

    public static String vector(List<? extends Number> values, int dimension) throws IOException {
        if (values == null || values.size() != dimension) throw new IOException("Embedding dimension mismatch: expected " + dimension);
        StringJoiner result = new StringJoiner(",", "[", "]");
        for (Number value : values) {
            if (value == null || !Float.isFinite(value.floatValue())) throw new IOException("Non-finite embedding");
            result.add(Float.toString(value.floatValue()));
        }
        return result.toString();
    }

    /** Predicates and order clauses are fixed SQL supplied by owning stores; values are always bound. */
    public List<Point> search(List<Float> vector, String predicate, List<?> parameters, int limit, double minimum) throws IOException {
        String literal = vector(vector, dimension);
        return select("SELECT id, payload, 1 - (embedding <=> ?::vector) AS score FROM " + table
                        + " WHERE (" + predicate + ") AND (embedding <=> ?::vector) <= ?"
                        + " ORDER BY embedding <=> ?::vector, id LIMIT ?",
                concat(List.of(literal), parameters, List.of(literal, 1 - minimum, literal, limit)));
    }

    public List<Point> list(String predicate, List<?> parameters, String order, int limit) throws IOException {
        boolean unlimited = limit == Integer.MAX_VALUE;
        return select("SELECT id, payload, 0 AS score FROM " + table + " WHERE " + predicate
                + " ORDER BY " + order + (unlimited ? "" : " LIMIT ?"), unlimited ? parameters : concat(parameters, List.of(limit)));
    }

    private List<Point> select(String sql, List<?> parameters) throws IOException {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, parameters);
            List<Point> result = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) result.add(new Point(r.getObject("id", UUID.class),
                        JsonParser.parseString(r.getString("payload")).getAsJsonObject(), r.getDouble("score")));
            }
            return result;
        } catch (SQLException e) { throw new IOException("Vector query failed: " + table, e); }
    }

    public void upsert(UUID id, List<Float> embedding, JsonObject payload) throws IOException {
        execute("INSERT INTO " + table + " (id, embedding, payload) VALUES (?, ?::vector, ?::jsonb)"
                        + " ON CONFLICT (id) DO UPDATE SET embedding=EXCLUDED.embedding, payload=EXCLUDED.payload",
                List.of(id, vector(embedding, dimension), payload.toString()));
    }

    public void update(UUID id, List<Float> embedding, JsonObject patch) throws IOException {
        execute("UPDATE " + table + " SET embedding=?::vector, payload=payload || ?::jsonb WHERE id=?",
                List.of(vector(embedding, dimension), patch.toString(), id));
    }

    public void payload(UUID id, JsonObject patch) throws IOException {
        execute("UPDATE " + table + " SET payload=payload || ?::jsonb WHERE id=?", List.of(patch.toString(), id));
    }

    public void delete(UUID id) throws IOException { execute("DELETE FROM " + table + " WHERE id=?", List.of(id)); }

    private void execute(String sql, List<?> parameters) throws IOException {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, parameters);
            s.executeUpdate();
        } catch (SQLException e) { throw new IOException("Vector write failed: " + table, e); }
    }

    private static void bind(PreparedStatement s, List<?> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) s.setObject(i + 1, parameters.get(i));
    }

    @SafeVarargs private static List<Object> concat(List<?>... lists) {
        List<Object> result = new ArrayList<>();
        for (List<?> list : lists) result.addAll(list);
        return result;
    }
}
