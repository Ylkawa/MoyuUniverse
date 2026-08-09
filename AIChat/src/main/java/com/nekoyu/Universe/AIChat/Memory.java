package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.Providers.LLMProvider.Embedding;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.EmbeddingRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import com.nekoyu.Universe.API.Providers.Provider;
import com.nekoyu.Universe.Universe;
import io.qdrant.client.*;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static com.nekoyu.Universe.Utils.Time.formatTimestamp;
import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;

public class Memory {
    private final Embedding provider;
    private final String collection;
    private final Logger logger = LoggerFactory.getLogger(Memory.class);
    private final QdrantClient client;
    private final String vectorName;

    public Memory(Config.Qdrant config, Config.Memory memCfg) throws IOException {
        Provider provider = (Provider) Universe.Providers.get(config.provider);
        if (provider instanceof Embedding embedding) this.provider = embedding;
        else if (provider != null) throw new IllegalArgumentException("Provider must support Embedding");
        else throw new IllegalArgumentException("Please specify a Embedding provider");
        try {
            var builder = QdrantGrpcClient.newBuilder(config.address, config.port, config.encryptedConnection);
            if (config.secretKey != null) builder.withApiKey(config.secretKey);
            client = new QdrantClient(builder.build());
            collection = memCfg.collection;
            vectorName = memCfg.vectorName;
            boolean exists = client.collectionExistsAsync(collection).get();
            if (!exists) {
                logger.info("数据集 {} 不存在，将尝试自动创建.", collection);
                // 创建 collection
                client.createCollectionAsync(
                        collection,
                        Collections.VectorParams.newBuilder()
                                .setSize(embedding("test").length) // 这里会发起一次 Embedding 以得到指定的模型的向量维度数
                                .setDistance(Collections.Distance.Cosine)
                                .build()
                ).get();

                // createAt index
                client.createPayloadIndexAsync(
                        collection,
                        "create_at",
                        PayloadSchemaType.Integer,
                        null, null, null, null
                ).get();

                // updateAt index
                client.createPayloadIndexAsync(
                        collection,
                        "update_at",
                        Collections.PayloadSchemaType.Integer,
                        null, null, null, null
                ).get();

                // locationId index
                client.createPayloadIndexAsync(
                        collection,
                        "location_id",
                        Collections.PayloadSchemaType.Keyword,
                        null, null, null, null
                ).get();

                logger.info("数据集已建立.");
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Item> query(List<Float> queryVector, List<String> locationIds) {
        try {
            Common.Condition notDeletedCondition = Common.Condition.newBuilder()
                    .setField(
                            Common.FieldCondition.newBuilder()
                                    .setKey("confidence")
                                    .setRange(
                                            Common.Range.newBuilder()
                                                    .setGte(0)
                                                    .build()
                                    )
                                    .build()
                    )
                    .build();

            Common.Filter.Builder filterBuilder = Common.Filter.newBuilder()
                    .addMust(notDeletedCondition);

            List<Common.Condition> locationConditions = locationIds.stream()
                    .filter(id -> id != null && !id.isEmpty())
                    .map(id -> Common.Condition.newBuilder()
                            .setField(
                                    Common.FieldCondition.newBuilder()
                                            .setKey("location_id")
                                            .setMatch(
                                                    Common.Match.newBuilder()
                                                            .setKeyword(id)
                                                            .build()
                                            )
                                            .build()
                            )
                            .build()
                    )
                    .toList();

            if (!locationConditions.isEmpty()) {
                filterBuilder.addMust(
                        Common.Condition.newBuilder()
                                .setFilter(
                                        Common.Filter.newBuilder()
                                                .addAllShould(locationConditions)
                                                .build()
                                )
                                .build()
                );
            }

            List<Points.ScoredPoint> result = client.searchAsync(
                    Points.SearchPoints.newBuilder()
                            .setCollectionName(collection)
                            .setVectorName(vectorName)
                            .addAllVector(queryVector)
                            .setFilter(filterBuilder.build())
                            .setLimit(10)
                            .setScoreThreshold(0.7f)
                            .setWithPayload(
                                    Points.WithPayloadSelector.newBuilder()
                                            .setEnable(true)
                                            .build()
                            )
                            .build()
            ).get();

            List<Item> items = new ArrayList<>();
            for (Points.ScoredPoint point : result) {
                Item item = new Item();
                item.score = point.getScore();

                if (point.hasId() && point.getId().hasUuid()) {
                    item.id = UUID.fromString(point.getId().getUuid());
                }

                var payload = point.getPayloadMap();

                if (payload.containsKey("content")) {
                    item.content = payload.get("content").getStringValue();
                }
                // createAt 创建日期
                if (payload.containsKey("create_at")) {
                    item.createAt = payload.get("create_at").getIntegerValue();
                }
                // updatedAt 修改日期
                if (payload.containsKey("update_at")) {
                    item.updateAt = payload.get("update_at").getIntegerValue();
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
                if (item.id == null) {
                    item.id = UUID.randomUUID();
                }
                item.createAt = System.currentTimeMillis();
                item.updateAt = System.currentTimeMillis();
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
                        "update_at",
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
                                        .setUuid(String.valueOf(item.id))
                                        .build()
                        )
                        .setVectors(namedVectors(Map.of(vectorName, vector(vector))))
                        .putAllPayload(payload)
                        .build();

                points.add(point);
            }

            client.upsertAsync(collection, points).get();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void update(UUID uuid, float confidence, String content) {
        try {
            double[] embedding = embedding(content);
            List<Float> vector = new ArrayList<>();
            for (double v : embedding) {
                vector.add((float) v);
            }

            Map<String, Value> payload = new HashMap<>();
            payload.put("update_at", ValueFactory.value(System.currentTimeMillis()));
            payload.put("confidence", ValueFactory.value(confidence));
            payload.put("content", ValueFactory.value(content));

            Points.PointStruct point = Points.PointStruct.newBuilder()
                    .setId(PointIdFactory.id(uuid))
                    .setVectors(namedVectors(Map.of(vectorName, vector(vector))))
                    .putAllPayload(payload)
                    .build();

            client.upsertAsync(collection, List.of(point)).get();
        } catch (Exception e) {
            logger.error("Update failed", e);
        }
    }

    /**
     * 此方法不会真实删除数据，只会对已有数据进行隐藏（置信度改到-1.0）
     */
    public void delete(UUID uuid) {
        try {
            client.setPayloadAsync(
                    collection,
                    Map.of(
                            "confidence", ValueFactory.value(-1.0F)
                    ),
                    List.of(PointIdFactory.id(uuid)), // 确保 uuid 转换为了 UUID 对象
                    true, // wait: 是否等待操作在服务端落盘后再返回
                    null, // ordering: 排序保证，传 null 使用默认值
                    null  // shardKey: 分片键，传 null 使用默认值
            ).get();
        } catch (Exception e) {
            logger.error("Delete failed", e);
        }
    }

    public List<Item> getLastMemoryItems(List<String> locationIds, int limit) {
        if (locationIds == null || locationIds.isEmpty()) {
            return List.of();
        }

        List<Common.Condition> locationConditions = new ArrayList<>();

        for (String locationId : locationIds) {
            if (locationId != null) locationConditions.add(
                    Common.Condition.newBuilder()
                            .setField(
                                    Common.FieldCondition.newBuilder()
                                            .setKey("location_id")
                                            .setMatch(
                                                    Common.Match.newBuilder()
                                                            .setKeyword(locationId)
                                                            .build()
                                            )
                                            .build()
                            ).build()
            );
        }

        Common.Condition notDeletedCondition = Common.Condition.newBuilder()
                .setField(
                        Common.FieldCondition.newBuilder()
                                .setKey("confidence")
                                .setRange(
                                        Common.Range.newBuilder()
                                                .setGte(0)
                                                .build()
                                )
                                .build()
                )
                .build();

        Common.Filter filter = Common.Filter.newBuilder()
                .addAllShould(locationConditions)
                .addMust(notDeletedCondition)
                .build();

        try {
            Points.ScrollResponse result = client.scrollAsync(
                    Points.ScrollPoints.newBuilder()
                            .setCollectionName(collection)
                            .setFilter(filter)
                            .setLimit(limit)
                            .setWithPayload(Points.WithPayloadSelector.newBuilder()
                                    .setEnable(true)
                                    .build())
                            .build()
            ).get();

            List<Item> items = new ArrayList<>();

            for (Points.RetrievedPoint point : result.getResultList()) {
                Map<String, Value> payload = point.getPayloadMap();

                Item item = new Item();

                // id
                if (point.getId().hasUuid()) {
                    item.id = UUID.fromString(point.getId().getUuid());
                } else if (point.getId().hasNum()) {
                    item.id = UUID.fromString(String.valueOf(point.getId().getNum()));
                }

                // payload
                item.content = payload.containsKey("content")
                        ? payload.get("content").getStringValue()
                        : null;

                item.locationId = payload.containsKey("location_id")
                        ? payload.get("location_id").getStringValue()
                        : null;

                item.createAt = payload.containsKey("create_at")
                        ? payload.get("create_at").getIntegerValue()
                        : 0L;

                item.updateAt = payload.containsKey("update_at")
                        ? payload.get("update_at").getIntegerValue()
                        : 0L;

                item.score = payload.containsKey("score")
                        ? (float) payload.get("score").getDoubleValue()
                        : 0F;

                item.confidence = payload.containsKey("confidence")
                        ? (float) payload.get("confidence").getDoubleValue()
                        : 0F;

                items.add(item);
            }

            // 按 createAt 倒序
            items.sort((a, b) -> Long.compare(b.createAt, a.createAt));

            return items;
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public double[] embedding(String text) throws IOException {
        EmbeddingRequest embeddingRequest = new EmbeddingRequest();
        MFChain mfc = new MFChain();
        mfc.add(new TextField(text));
        embeddingRequest.message.add(mfc);
        EmbeddingResponse resp = provider.embedding(embeddingRequest);
        if (resp == null || resp.data == null || resp.data.isEmpty()) throw new IOException("Empty response");
        return resp.data.get(0).embedding;
    }

    public static class Item {
        public UUID id = UUID.randomUUID();
        public String content;
        public long createAt;
        public long updateAt;
        public String locationId;
        public float score;
        public float confidence;

        @Override
        public String toString() {
            return "|time=" + formatTimestamp(updateAt)
                    + "|confidence=" + confidence
                    + "|loc=" + locationId + "]: "
                    + content;
        }
    }
}
