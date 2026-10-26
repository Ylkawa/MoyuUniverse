package com.nekoyu.Universe.AIChat.knowledge;

import com.google.gson.Gson;
import com.nekoyu.Universe.AIChat.PgVectorTable;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.Providers.LLMProvider.Embedding;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Reports, evidence and topic vectors share one PostgreSQL row and the existing connection pool. */
public final class ResearchStore {
    private static final Gson GSON = new Gson();
    private final DataSource dataSource;

    private final Embedding embedding;

    private final String embeddingModel;
    private final int dimension;
    private final String table;

    ResearchStore(DataSource dataSource, Embedding embedding, String embeddingModel, int dimension) {
        this(dataSource, embedding, embeddingModel, dimension, "aichat.research_task");
    }

    ResearchStore(DataSource dataSource, Embedding embedding, String embeddingModel, int dimension, String table) {
        this.dataSource = dataSource;
        this.embedding = embedding;
        this.embeddingModel = embeddingModel;
        this.dimension = dimension;
        this.table = PgVectorTable.sqlName(table);
    }

    public void archiveResearch(Researches research) throws IOException {
        if (research.report() == null || research.report().isBlank())
            throw new IllegalArgumentException("Cannot archive an incomplete research report");
        // 先保存正文。向量服务失败不会丢失完整报告；同一对象重试使用同一个 UUID。
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO %s
                         (id, topic, question, status, report, last_request, evidence, created_at, finished_at)
                     VALUES (?, ?, ?, 'completed', ?, ?::jsonb, ?::jsonb, ?, ?)
                     ON CONFLICT (id) DO UPDATE SET
                         topic = EXCLUDED.topic, question = EXCLUDED.question, status = 'completed',
                         report = EXCLUDED.report, last_request = EXCLUDED.last_request, evidence = EXCLUDED.evidence,
                         error = NULL, finished_at = EXCLUDED.finished_at
                     """.formatted(table))) {
            statement.setObject(1, research.id());
            statement.setString(2, research.topic());
            statement.setString(3, research.question());
            statement.setString(4, research.report());
            JsonObject request = research.lastRequest();
            statement.setString(5, request == null ? null : request.toString());
            statement.setString(6, GSON.toJson(research.evidence()));
            statement.setObject(7, Instant.ofEpochMilli(research.createdAt()).atOffset(ZoneOffset.UTC));
            statement.setObject(8, Instant.ofEpochMilli(research.completedAt()).atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IOException("Research report archive failed", error);
        }
        // Preserve the report even when its embedding provider fails; a retry updates the same row.
        List<Float> topicVector = embed(research.topic());
        try (Connection c = dataSource.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE " + table + " SET embedding=?::vector WHERE id=?")) {
            s.setString(1, PgVectorTable.vector(topicVector, dimension));
            s.setObject(2, research.id());
            s.executeUpdate();
        } catch (SQLException error) {
            throw new IOException("Research vector archive failed", error);
        }
    }

    public List<Researches> searchResearches(String topic) throws IOException {
        if (topic == null || topic.isBlank()) return List.of();
        List<Float> topicVector = embed(topic.strip());
        List<Researches> results = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, topic, question, report, last_request, evidence, created_at, finished_at
                     FROM %s
                     WHERE status = 'completed' AND report IS NOT NULL AND finished_at IS NOT NULL
                       AND embedding IS NOT NULL AND (embedding <=> ?::vector) <= ?
                     ORDER BY embedding <=> ?::vector, id LIMIT 3
                     """.formatted(table))) {
            String literal = PgVectorTable.vector(topicVector, dimension);
            statement.setString(1, literal);
            statement.setDouble(2, 1 - 0.6);
            statement.setString(3, literal);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    UUID id = row.getObject("id", UUID.class);
                    String report = row.getString("report");
                    String json = row.getString("last_request");
                    JsonObject request = json == null ? null : JsonParser.parseString(json).getAsJsonObject();
                    String evidenceJson = row.getString("evidence");
                    // 新归档精确保留观察目录；显式空对象不能回退到上下文并扩张来源。
                    Map<String, String> evidence = evidenceJson == null
                            ? restoreEvidence(row.getString("question"), report, request) : parseEvidence(evidenceJson);
                    results.add(new Researches(id, row.getString("topic"), row.getString("question"), report,
                            evidence,
                            row.getObject("created_at", OffsetDateTime.class).toInstant().toEpochMilli(),
                            row.getObject("finished_at", OffsetDateTime.class).toInstant().toEpochMilli(), request));
                }
            }
        } catch (SQLException error) {
            throw new IOException("Research report lookup failed", error);
        }
        return results;
    }

    private List<Float> embed(String topic) throws IOException {
        EmbeddingRequest request = new EmbeddingRequest();
        request.model = embeddingModel;
        request.dimension = dimension;
        MFChain input = new MFChain();
        input.add(new TextField(topic));
        request.message.add(input);
        var response = embedding.embedding(request);
        if (response == null || response.data == null || response.data.size() != 1
                || response.data.get(0).embedding == null || response.data.get(0).embedding.length != dimension)
            throw new IOException("Research topic embedding dimension/count mismatch");
        List<Float> result = new ArrayList<>(dimension);
        for (double value : response.data.get(0).embedding) {
            float number = (float) value;
            if (!Float.isFinite(number)) throw new IOException("Non-finite research topic embedding");
            result.add(number);
        }
        return result;
    }

    /** 记录 Provider 接口收到的逻辑请求；不包含凭据、回调对象或 Provider 私有的 HTTP 封装。 */
    static JsonObject snapshotRequest(String model, List<Message> messages, List<LLMFunction> tools,
                                      CompletionsRequest options) {
        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.add("messages", GSON.toJsonTree(messages));
        request.add("tools", GSON.toJsonTree(tools));
        request.add("options", GSON.toJsonTree(options));
        return request;
    }

    private static Map<String, String> parseEvidence(String json) throws IOException {
        try {
            Map<String, String> result = new LinkedHashMap<>();
            for (var entry : JsonParser.parseString(json).getAsJsonObject().entrySet()) {
                var value = entry.getValue();
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("Expected evidence text");
                result.put(entry.getKey(), value.getAsString());
            }
            return result;
        } catch (RuntimeException error) {
            throw new IOException("Invalid archived research evidence", error);
        }
    }

    /** 兼容旧归档：仅恢复原始问题与工具观察，不把系统拼装的历史 user 材料当作原始来源。 */
    static Map<String, String> restoreEvidence(String question, String report, JsonObject request) {
        ResearchEvidence evidence = new ResearchEvidence(question);
        if (request != null && request.has("messages")) {
            List<Message> messages = new ArrayList<>();
            for (var item : request.getAsJsonArray("messages")) {
                JsonObject json = item.getAsJsonObject();
                StringBuilder text = new StringBuilder();
                if (json.has("content")) for (var piece : json.getAsJsonArray("content")) {
                    JsonObject content = piece.getAsJsonObject();
                    if (content.has("text")) text.append(content.get("text").getAsString());
                }
                Message message = new Message(text.toString());
                message.role = json.has("role") ? json.get("role").getAsString() : null;
                message.tool_call_id = json.has("tool_call_id") ? json.get("tool_call_id").getAsString() : null;
                message.tool_calls = GSON.fromJson(json.get("tool_calls"), Tool_call[].class);
                messages.add(message);
            }
            evidence.observeTools(messages);
        }
        return evidence.forReport(report);
    }
}
