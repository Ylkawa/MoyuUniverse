package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.Providers.LLMProvider.Embedding;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.EmbeddingRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import com.nekoyu.Universe.API.Providers.Provider;
import com.nekoyu.Universe.Universe;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.JsonWithInt.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.VectorsFactory.vectors;

public class Memory {
    private final Embedding provider;
    private Logger logger = LoggerFactory.getLogger(Memory.class);
    private QdrantClient client;
    private String collection;

    public Memory(Config.QdrantConfig config) throws IOException {
        var builder = QdrantGrpcClient.newBuilder(config.address, config.port, config.encryptedConnection);
        if (config.secretKey != null) builder.withApiKey(config.secretKey);
        client = new QdrantClient(builder.build());
        collection = config.collection;
        Provider provider = (Provider) Universe.Providers.get(config.provider);
        if (provider instanceof Embedding embedding) this.provider = embedding;
        else if (provider != null) throw new IllegalArgumentException("Provider must support Embedding");
        else throw new IllegalArgumentException("Please specify a Embedding provider");
    }

    public List<Item> query(String quiz) throws IOException {
        List<Float> queryVector = new ArrayList<>();
        for (double v : embedding(quiz)) {
            queryVector.add((float) v);
        }
        try {
            List<Points.ScoredPoint> result = client.searchAsync(
                    Points.SearchPoints.newBuilder()
                            .setCollectionName(collection)
                            .addAllVector(queryVector)
                            .setLimit(10)
                            .build()
            ).get();
            List<Item> items = new ArrayList<>();
            for (Points.ScoredPoint point : result) {
                Item item = new Item();
                item.score = point.getScore();
                // point id
                if (point.hasId() && point.getId().hasUuid()) {
                    item.id = point.getId().getUuid();
                }
                var payload = point.getPayloadMap();
                // content
                if (payload.containsKey("content")) {
                    item.content = payload.get("content").getStringValue();
                }
                // createAt 创建日期
                if (payload.containsKey("create_at")) {
                    item.createAt = payload.get("create_at").getIntegerValue();
                }
                // updatedAt 修改日期
                if (payload.containsKey("updated_at")) {
                    item.updateAt = payload.get("updated_at").getIntegerValue();
                }
                // locationId
                if (payload.containsKey("location_id")) {
                    item.locationId = payload.get("location_id").getStringValue();
                }
                // confidence 置信度
                if (payload.containsKey("confidence")) {
                    item.confidence = (float) payload.get("confidence").getDoubleValue();
                }
                items.add(item);
            }

            return items;
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public void insert(List<Item> items) {
        try {
            List<Points.PointStruct> points = new ArrayList<>();

            for (Item item : items) {
                // 自动生成 UUID
                if (item.id == null || item.id.isBlank()) {
                    item.id = UUID.randomUUID().toString();
                }
                // embedding
                double[] embedding = embedding(item.content);
                List<Float> vector = new ArrayList<>();
                for (double v : embedding) {
                    vector.add((float) v);
                }
                // payload
                Map<String, Value> payload = new HashMap<>();
                payload.put(
                        "content",
                        Value.newBuilder()
                                .setStringValue(item.content)
                                .build()
                );
                payload.put(
                        "create_at",
                        Value.newBuilder()
                                .setIntegerValue(item.createAt)
                                .build()
                );
                payload.put(
                        "updated_at",
                        Value.newBuilder()
                                .setIntegerValue(item.updateAt)
                                .build()
                );
                payload.put(
                        "location_id",
                        Value.newBuilder()
                                .setStringValue(item.locationId)
                                .build()
                );
                payload.put(
                        "confidence",
                        Value.newBuilder()
                                .setDoubleValue(item.confidence)
                                .build()
                );
                // point
                Points.PointStruct point = Points.PointStruct.newBuilder()
                        .setId(
                                io.qdrant.client.grpc.Common.PointId.newBuilder()
                                        .setUuid(item.id)
                                        .build()
                        )
                        .setVectors(vectors(vector))
                        .putAllPayload(payload)
                        .build();

                points.add(point);
            }

            client.upsertAsync(collection, points).get();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public double[] embedding(String text) throws IOException {
        EmbeddingRequest embeddingRequest = new EmbeddingRequest();
        embeddingRequest.message = new MFChain();
        embeddingRequest.message.add(new TextField(text));
        EmbeddingResponse resp = provider.embedding(embeddingRequest);
        if (resp == null || resp.data == null || resp.data.isEmpty()) throw new IOException("Empty response");
        return resp.data.get(0).embedding;
    }

    public static class Item {
        public String id;
        public String content;
        public long createAt;
        public long updateAt;
        public String locationId;
        public float score;
        public float confidence;
    }
}
