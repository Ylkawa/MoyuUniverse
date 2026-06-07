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
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;

public class ExternalKnowledgeBase {
    private final Embedding provider;
    private final String collection;
    private final Logger logger = LoggerFactory.getLogger(ExternalKnowledgeBase.class);
    private final QdrantClient client;
    private final String vectorName;
    private final Config.ExternalKnowledgeBase ekbCfg;

    public ExternalKnowledgeBase(Config.Qdrant config, Config.ExternalKnowledgeBase ekbCfg) throws IOException {
        this.ekbCfg = ekbCfg;
        Provider provider = (Provider) Universe.Providers.get(config.provider);

        if (provider instanceof Embedding embedding) {
            this.provider = embedding;
        } else if (provider != null) {
            throw new IllegalArgumentException("Provider must support Embedding");
        } else {
            throw new IllegalArgumentException("Please specify a Embedding provider");
        }

        try {
            var builder = QdrantGrpcClient.newBuilder(
                    config.address,
                    config.port,
                    config.encryptedConnection
            );

            if (config.secretKey != null) {
                builder.withApiKey(config.secretKey);
            }

            client = new QdrantClient(builder.build());

            collection = ekbCfg.collection;
            vectorName = ekbCfg.vectorName;

            initCollection();

        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Item> query(List<Float> vector, Conditions conditions) throws ExecutionException, InterruptedException {
        var filterBuilder = Common.Filter.newBuilder();
        if (conditions.subject != null && !conditions.subject.isEmpty()) {
            Common.Condition condition =
                    Common.Condition.newBuilder()
                            .setField(
                                    Common.FieldCondition.newBuilder()
                                            .setKey("subject")
                                            .setMatch(
                                                    Common.Match.newBuilder()
                                                            .setKeyword(conditions.subject)
                                                            .build()
                                            )
                                            .build()
                            )
                            .build();
            filterBuilder.addMust(condition);
        }

        List<Points.ScoredPoint> result = client.searchAsync(
                Points.SearchPoints.newBuilder()
                        .setCollectionName(collection)
                        .addAllVector(vector)
                        .setVectorName(vectorName)
                        .setLimit(10)
                        .setScoreThreshold(0.8f)
                        .setFilter(filterBuilder.build())
                        .setWithPayload(
                                Points.WithPayloadSelector.newBuilder()
                                        .setEnable(true)
                                        .build()
                        )
                        .build()
        ).get();
        List<Item> items = new ArrayList<>();
        for (var obj : result) {
            items.add(new Item(UUID.fromString(obj.getId().getUuid()), obj.getPayloadMap()));
        }
        return items;
    }

    public void insert(List<Item> items) {
        List<Points.PointStruct> points = new ArrayList<>();
        try {
            List<List<Float>> vectors = embedding(items);
            int i = 0;
            for (var item : items) {
                Points.PointStruct point = Points.PointStruct.newBuilder()
                        .setId(
                                Common.PointId.newBuilder()
                                        .setUuid(item.id.toString())
                                        .build()
                        )
                        .setVectors(
                                namedVectors(Map.of(
                                        vectorName, vector(vectors.get(i))
                                )))
                        .putAllPayload(
                                item.toPayload()
                        )
                        .build();

                points.add(point);
                i++;
            }

            client.upsertAsync(
                    collection,
                    points
            ).get();
        } catch (IOException | InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private List<List<Float>> embedding(List<Item> items) throws IOException {
        EmbeddingRequest request = new EmbeddingRequest();
        for (var item : items) {
            MFChain mfc = new MFChain();
            mfc.add(new TextField(item.content));
            request.message.add(mfc);
        }
        EmbeddingResponse response = provider.embedding(request);
        List<List<Float>> embeddings = new ArrayList<>();
        for (var data : response.data) {
            List<Float> embedding = new ArrayList<>();
            for (var a : data.embedding) {
                embedding.add((float) a);
            }
            embeddings.add(embedding);
        }
        return embeddings;
    }

    private void initCollection()
            throws InterruptedException, ExecutionException {

        boolean exists = client.collectionExistsAsync(collection).get();

        if (exists) return;

        logger.info("知识库 {} 不存在，将自动创建", collection);

        client.createCollectionAsync(
                collection,
                Collections.VectorParams.newBuilder()
                        .setSize(ekbCfg.dimension)
                        .setDistance(Collections.Distance.Cosine)
                        .build()
        ).get();

        createIndex("subject", Collections.PayloadSchemaType.Keyword);
        createIndex("source", Collections.PayloadSchemaType.Keyword);

        createIndex("create_at", Collections.PayloadSchemaType.Integer);
        createIndex("update_at", Collections.PayloadSchemaType.Integer);

        logger.info("知识库 {} 创建完成", collection);
    }

    private void createIndex(
            String field,
            Collections.PayloadSchemaType type
    ) throws InterruptedException, ExecutionException {

        client.createPayloadIndexAsync(
                collection,
                field,
                type,
                null,
                null,
                null,
                null
        ).get();
    }

    public static class Item {
        public UUID id;
        public String content;
        public String subject;
        public String source;
        public float confidence;
        public float importance;
        /**
         * 衰减速度
         * 0 = 永久知识
         * 1 = 正常
         * >1 = 快速过期
         */
        public float decayRate = 1F;
        public long createdAt;
        public long updatedAt;
        public float score;

        public Item() {
            this.id = UUID.randomUUID();
        }

        public Item(UUID uuid, Map<String, JsonWithInt.Value> payload) {
            this.content = payload.get("content").getStringValue();
            this.subject = payload.get("subject").getStringValue();
            this.source = payload.get("source").getStringValue();
            this.confidence = (float) payload.get("confidence").getDoubleValue();
            this.importance = (float) payload.get("importance").getDoubleValue();
            this.decayRate = (float) payload.get("decay_rate").getDoubleValue();
            this.createdAt = payload.get("created_at").getIntegerValue();
            this.updatedAt = payload.get("updated_at").getIntegerValue();
            this.score = (float) payload.get("score").getDoubleValue();
            this.id = uuid;
        }

        public HashMap<String, JsonWithInt.Value> toPayload() {
            HashMap<String, JsonWithInt.Value> payload = new HashMap<>();
            payload.put(
                    "content",
                    JsonWithInt.Value.newBuilder()
                            .setStringValue(content)
                            .build()
            );
            payload.put(
                    "subject",
                    JsonWithInt.Value.newBuilder().setStringValue(subject).build()
            );
            payload.put(
                    "source",
                    JsonWithInt.Value.newBuilder().setStringValue(source).build()
            );
            payload.put(
                    "confidence",
                    JsonWithInt.Value.newBuilder().setDoubleValue(confidence).build()
            );
            payload.put(
                    "importance",
                    JsonWithInt.Value.newBuilder().setDoubleValue(importance).build()
            );
            payload.put(
                    "decay_rate",
                    JsonWithInt.Value.newBuilder().setDoubleValue(decayRate).build()
            );
            payload.put(
                    "created_at",
                    JsonWithInt.Value.newBuilder().setIntegerValue(createdAt).build()
            );
            payload.put(
                    "updated_at",
                    JsonWithInt.Value.newBuilder().setIntegerValue(updatedAt).build()
            );
            payload.put(
                    "score",
                    JsonWithInt.Value.newBuilder().setDoubleValue(score).build()
            );
            return payload;
        }
    }

    public static class Conditions {
        String subject;
    }
}