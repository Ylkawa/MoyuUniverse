package com.nekoyu.Universe.AIChat;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.util.*;

final class MemoryTable {
    private static final String COLUMNS = "id, content, location_id, create_at, update_at, confidence, importance";
    private final DataSource dataSource;
    private final String table;
    private final int dimension;

    MemoryTable(DataSource dataSource, String table, int dimension) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.table = PgVectorTable.sqlName(table);
        if (dimension < 1 || dimension > 2000) throw new IllegalArgumentException("Vector dimension must be 1..2000");
        this.dimension = dimension;
    }

    public void initialize() throws IOException {
        try (Connection c = dataSource.getConnection()) { initialize(c); }
        catch (SQLException e) { throw new IOException("Cannot initialize " + table, e); }
    }

    private void initialize(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS vector");
            s.execute("CREATE SCHEMA IF NOT EXISTS " + table.split("\\.")[0]);
            s.execute("CREATE TABLE IF NOT EXISTS " + table + " (id uuid PRIMARY KEY, embedding vector(" + dimension
                    + ") NOT NULL, content text, location_id text, create_at bigint, update_at bigint, confidence double precision, importance double precision)");
            s.execute("CREATE INDEX IF NOT EXISTS " + index(table, "embedding") + " ON " + table
                    + " USING hnsw (embedding vector_cosine_ops)");
            s.execute("CREATE INDEX IF NOT EXISTS " + index(table, "location_created") + " ON " + table + " (location_id, create_at DESC NULLS LAST, id) WHERE confidence >= 0");
        }
        PgVectorTable.requireDimension(c, table, dimension);
    }

    private static String index(String table, String suffix) { return table.substring(table.indexOf('.') + 1) + "_" + suffix + "_idx"; }

    /** Predicates and order clauses are fixed SQL supplied by owning stores; values are always bound. */
    public List<Memory.Item> search(List<Float> vector, String predicate, List<?> parameters, int limit, double minimum) throws IOException {
        String literal = PgVectorTable.vector(vector, dimension);
        return select("SELECT " + COLUMNS + ", 1 - (embedding <=> ?::vector) AS score FROM " + table
                        + " WHERE (" + predicate + ") AND (embedding <=> ?::vector) <= ?"
                        + " ORDER BY embedding <=> ?::vector, id LIMIT ?",
                concat(List.of(literal), parameters, List.of(literal, 1 - minimum, literal, limit)));
    }

    public List<Memory.Item> list(String predicate, List<?> parameters, String order, int limit) throws IOException {
        boolean unlimited = limit == Integer.MAX_VALUE;
        return select("SELECT " + COLUMNS + ", 0 AS score FROM " + table + " WHERE " + predicate
                + " ORDER BY " + order + (unlimited ? "" : " LIMIT ?"), unlimited ? parameters : concat(parameters, List.of(limit)));
    }

    private List<Memory.Item> select(String sql, List<?> parameters) throws IOException {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, parameters);
            List<Memory.Item> result = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    Memory.Item item = new Memory.Item();
                    item.id = r.getObject("id", UUID.class);
                    item.content = r.getString("content");
                    item.locationId = r.getString("location_id");
                    item.createAt = r.getLong("create_at");
                    item.updateAt = r.getLong("update_at");
                    item.confidence = r.getFloat("confidence");
                    item.importance = r.getFloat("importance");
                    if (r.wasNull()) item.importance = 0.5f;
                    item.score = r.getFloat("score");
                    result.add(item);
                }
            }
            return result;
        } catch (SQLException e) { throw new IOException("Vector query failed: " + table, e); }
    }

    void upsert(Memory.Item item, List<Float> embedding) throws IOException {
        execute("INSERT INTO " + table + " (" + COLUMNS + ", embedding) VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector)"
                        + " ON CONFLICT (id) DO UPDATE SET content=EXCLUDED.content, location_id=EXCLUDED.location_id,"
                        + " create_at=EXCLUDED.create_at, update_at=EXCLUDED.update_at, confidence=EXCLUDED.confidence,"
                        + " importance=EXCLUDED.importance, embedding=EXCLUDED.embedding",
                Arrays.asList(item.id, item.content, item.locationId, item.createAt, item.updateAt,
                        (double) item.confidence, (double) item.importance, PgVectorTable.vector(embedding, dimension)));
    }

    void update(UUID id, List<Float> embedding, String content, long updated, float confidence, float importance) throws IOException {
        execute("UPDATE " + table + " SET embedding=?::vector, content=?, update_at=?, confidence=?, importance=? WHERE id=?",
                Arrays.asList(PgVectorTable.vector(embedding, dimension), content, updated, (double) confidence, (double) importance, id));
    }

    void hide(UUID id) throws IOException {
        execute("UPDATE " + table + " SET confidence=-1 WHERE id=?", List.of(id));
    }

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
