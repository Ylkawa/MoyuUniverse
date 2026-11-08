package com.nekoyu.Universe.AIChat;

import java.io.IOException;
import java.sql.*;
import java.util.*;

public final class PgVectorTable {
    private PgVectorTable() {}

    public static String sqlName(String name) {
        if (name == null || !name.matches("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*"))
            throw new IllegalArgumentException("Expected schema.table: " + name);
        return name;
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

    public static String vector(List<? extends Number> values, int dimension) throws IOException {
        if (values == null || values.size() != dimension) throw new IOException("Embedding dimension mismatch: expected " + dimension);
        StringJoiner result = new StringJoiner(",", "[", "]");
        for (Number value : values) {
            if (value == null || !Float.isFinite(value.floatValue())) throw new IOException("Non-finite embedding");
            result.add(Float.toString(value.floatValue()));
        }
        return result.toString();
    }
}
