package com.nekoyu.Universe.AIChat.knowledge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.Providers.LLMProvider.Embedding;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.Points;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;

/** 完整报告存 PostgreSQL；Qdrant 仅保存主题向量，以任务 UUID 关联。复用知识库连接，不持有独立线程。 */
public final class ResearchStore {
    private static final Gson GSON = new Gson();
    private final DataSource dataSource;
    private final QdrantClient client;
    private final Embedding embedding;
    private final String collection;
    private final String vectorName;
    private final String embeddingModel;
    private final int dimension;
    private boolean collectionReady;

    ResearchStore(DataSource dataSource, QdrantClient client, Embedding embedding,
                  String collection, String vectorName, String embeddingModel, int dimension) {
        this.dataSource = dataSource;
        this.client = client;
        this.embedding = embedding;
        this.collection = collection;
        this.vectorName = vectorName;
        this.embeddingModel = embeddingModel;
        this.dimension = dimension;
    }

    public void archiveResearch(Researches research) throws IOException {
        if (research.report() == null || research.report().isBlank())
            throw new IllegalArgumentException("Cannot archive an incomplete research report");
        // 先保存正文。向量服务失败不会丢失完整报告；同一对象重试使用同一个 UUID。
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO aichat.research_task
                         (id, topic, question, status, report, last_request, created_at, finished_at)
                     VALUES (?, ?, ?, 'completed', ?, ?::jsonb, ?, ?)
                     ON CONFLICT (id) DO UPDATE SET
                         topic = EXCLUDED.topic, question = EXCLUDED.question, status = 'completed',
                         report = EXCLUDED.report, last_request = EXCLUDED.last_request,
                         error = NULL, finished_at = EXCLUDED.finished_at
                     """)) {
            statement.setObject(1, research.id());
            statement.setString(2, research.topic());
            statement.setString(3, research.question());
            statement.setString(4, research.report());
            JsonObject request = research.lastRequest();
            statement.setString(5, request == null ? null : request.toString());
            statement.setObject(6, Instant.ofEpochMilli(research.createdAt()).atOffset(ZoneOffset.UTC));
            statement.setObject(7, Instant.ofEpochMilli(research.completedAt()).atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IOException("Research report archive failed", error);
        }
        List<Float> topicVector = embed(research.topic());
        initCollection();
        await(client.upsertAsync(Points.UpsertPoints.newBuilder()
                .setCollectionName(collection).setWait(true)
                .addPoints(Points.PointStruct.newBuilder()
                        .setId(Common.PointId.newBuilder().setUuid(research.id().toString()))
                        .setVectors(namedVectors(Map.of(vectorName, vector(topicVector)))))
                .build()));
    }

    public List<Researches> searchResearches(String topic) throws IOException {
        if (topic == null || topic.isBlank()) return List.of();
        List<Float> topicVector = embed(topic.strip());
        initCollection();
        List<Points.ScoredPoint> points = await(client.searchAsync(Points.SearchPoints.newBuilder()
                .setCollectionName(collection).setVectorName(vectorName).addAllVector(topicVector)
                .setLimit(3).setScoreThreshold(0.6f).build()));
        if (points.isEmpty()) return List.of();
        List<Researches> results = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, topic, question, report, last_request, created_at, finished_at
                     FROM aichat.research_task
                     WHERE id = ? AND status = 'completed' AND report IS NOT NULL AND finished_at IS NOT NULL
                     """)) {
            Set<UUID> seen = new HashSet<>();
            for (var point : points) {
                if (!point.getId().hasUuid()) continue;
                UUID id = UUID.fromString(point.getId().getUuid());
                if (!seen.add(id)) continue;
                statement.setObject(1, id);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) continue; // 已删除或尚未完成的任务不能成为历史材料。
                    String report = row.getString("report");
                    String json = row.getString("last_request");
                    JsonObject request = json == null ? null : JsonParser.parseString(json).getAsJsonObject();
                    results.add(new Researches(id, row.getString("topic"), row.getString("question"), report,
                            restoreEvidence(row.getString("question"), report, request),
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

    private synchronized void initCollection() throws IOException {
        if (collectionReady) return;
        if (!await(client.collectionExistsAsync(collection)))
            await(client.createCollectionAsync(collection, Map.of(vectorName, Collections.VectorParams.newBuilder()
                    .setSize(dimension).setDistance(Collections.Distance.Cosine).build())));
        collectionReady = true;
    }

    private static <T> T await(Future<T> future) throws IOException {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Research index interrupted", error);
        } catch (ExecutionException | TimeoutException error) {
            future.cancel(true);
            throw new IOException("Research index unavailable", error);
        }
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

    /** 从原请求恢复来源观察记录，不把报告里模型自行生成的网址变成已观察的证据。 */
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
                if ("user".equals(message.role)) evidence.observe("历史请求输入材料，未在本轮重新核查：\n" + text);
            }
            evidence.observeTools(messages);
        }
        return evidence.forReport(report);
    }
}
