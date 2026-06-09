package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.Providers.LLMProvider.Assistant;
import com.nekoyu.Universe.API.Providers.LLMProvider.Embedding;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.EmbeddingRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import com.nekoyu.Universe.Universe;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;

public class ExternalKnowledgeBase {
    private LLMProvider fetcherProvider;
    private LLMProvider parserProvider;
    private final Embedding embedding;
    private final String collection;
    private final Logger logger = LoggerFactory.getLogger(ExternalKnowledgeBase.class);
    private final QdrantClient client;
    private final String vectorName;
    private final Config.ExternalKnowledgeBase ekbCfg;

    public ExternalKnowledgeBase(Config.Qdrant config, Config.ExternalKnowledgeBase ekbCfg) throws IOException {
        this.ekbCfg = ekbCfg;
        try {
            embedding = (Embedding) Universe.Providers.get(ekbCfg.embeddingProvider);
        } catch (ClassCastException e) {
            logger.error("Embedding provider not found");
            throw new RuntimeException("Embedding provider not found");
        }
        LLMProvider llmProvider;
        try {
            llmProvider = (LLMProvider) Universe.Providers.get(ekbCfg.provider);
        } catch (ClassCastException e) {
            logger.error("LLM provider not found");
            throw new RuntimeException("LLM provider not found");
        }
        try {
            fetcherProvider = ekbCfg.webCatchAgent.fetcher.provider != null ? (LLMProvider) Universe.Providers.get(ekbCfg.webCatchAgent.fetcher.provider) : llmProvider;
        } catch (ClassCastException e) {
            logger.error("Fetcher LLMProvider invalid");
        }
        try {
            parserProvider = ekbCfg.webCatchAgent.parser.provider != null ? (LLMProvider) Universe.Providers.get(ekbCfg.webCatchAgent.parser.provider) : llmProvider;
        } catch (ClassCastException e) {
            logger.error("Parser LLMProvider invalid");
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

    /**
     * 只读查询数据库，注意调用这个方法查询会计算向量并产生消耗
     * @param question
     * @param conditions
     * @return
     * @throws IOException
     */
    public List<Item> query(String question, @Nullable Conditions conditions) throws IOException {
        List<Float> vector = embedding(List.of(question)).get(0);
        return query(vector, conditions);
    }

    /**
     * 只读查询知识库
     * @param vector 问题的向量
     * @param conditions 限定查询范围
     * @return 相关的知识条目
     * @throws IOException 查询中发生的意外情况
     */
    public List<Item> query(List<Float> vector, @Nullable Conditions conditions) throws IOException {
        var filterBuilder = Common.Filter.newBuilder();
        if (conditions != null) {
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
        }

        List<Points.ScoredPoint> result;
        try {
            result = client.searchAsync(
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
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException(e);
        }
        List<Item> items = new ArrayList<>();
        for (var obj : result) {
            items.add(new Item(UUID.fromString(obj.getId().getUuid()), obj.getPayloadMap()));
        }
        return items;
    }

    public void insert(List<Item> items) {
        List<Points.PointStruct> points = new ArrayList<>();
        try {
            List<List<Float>> vectors = embeddingContent(items);
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

    private List<List<Float>> embeddingContent(List<Item> items) throws IOException {
        List<String> strings = new ArrayList<>();
        items.forEach(item -> strings.add(item.content));
        return embedding(strings);
    }

    private List<List<Float>> embedding(List<String> strings) throws IOException {
        EmbeddingRequest request = new EmbeddingRequest();
        for (var string : strings) {
            MFChain mfc = new MFChain();
            mfc.add(new TextField(string));
            request.message.add(mfc);
        }
        EmbeddingResponse response = embedding.embedding(request);
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

    public void webCatch(@NotNull String quiz) throws IOException {
        Assistant fetcher = new Assistant(this.fetcherProvider, ekbCfg.webCatchAgent.fetcher.model);
        fetcher.setThinking(true);
        fetcher.setSystemPromptFirst(ekbCfg.webCatchAgent.fetcher.promptFirst);
        fetcher.setSystemPromptLast(ekbCfg.webCatchAgent.fetcher.promptLast);
        if (ekbCfg.webCatchAgent.fetcher.tools != null) for (String tool : ekbCfg.webCatchAgent.fetcher.tools) {
            AIChat.llmFunctions.get(tool).forEach(fetcher::addTool);
        }
        MessageList ml = new MessageList();
        ml.add(MCMessage.Builder()
                .add(new TextField(quiz))
                .build());
        CompletionsResponse fetcherResponse = fetcher.completions(ml, null);
        String fetchContent = fetcherResponse.choices[0].message.content; // 得到从互联网上总结出的内容
        // 格式化信息
        Assistant parser = new Assistant(this.parserProvider, ekbCfg.webCatchAgent.parser.model);
        parser.setThinking(true);
        parser.setSystemPromptFirst(ekbCfg.webCatchAgent.parser.promptFirst);
        parser.setSystemPromptLast(ekbCfg.webCatchAgent.parser.promptLast);
        // 格式化信息不需要tools
        ml = new MessageList();
        ml.add(MCMessage.Builder()
                .add(new TextField("请根据如下信息，逐行输出能从中提炼出的信息"))
                .build());
        ml.add(MCMessage.Builder()
                .add(new TextField(fetchContent))
                .build());
        CompletionsResponse parseResponse = parser.completions(ml, null);
        String parseContent = parseResponse.choices[0].message.content; // 得到单行的信息
        List<String> strings = new ArrayList<>();
        for (var s : parseContent.split("\n")) {
            strings.add(s.trim());
        }
        List<List<Float>> vectors = embedding(strings); // 计算向量
        List<Item> existItems = new ArrayList<>(); // 并找回相关的知识条目
        for (var vector : vectors) {
            existItems.addAll(query(vector, null));
        }
        int i = 0;
        StringBuilder builder = new StringBuilder().append("先前知识库中已经存在的相关条目：\n");
        for (var item : existItems) {
            i++; // 从一开始往后面递增编号
            builder.append("[").append(i).append("] ").append(item.toString()).append("\n");
        }
        builder.append("""
                你需要保证知识条目不重复，如果预先想新增的知识条目与原有的条目重复，请根据实际情况，酌情删除或修改原有的知识条目，再新增新的知识条目
                输出严格遵循如下格式，且不输出其他多余内容，也不要对输出内容进行解释：
                NEW ([参数]): 内容
                UPDATE [条目编号] ([参数]): 内容
                DELETE [条目编号]
                
                例如：
                NEW (subject="崩坏星穹铁道", source="https://zh.moegirl.org.cn/%E4%B9%B1%E7%A0%B4", confidence=0.9, importance=0.7, decayRate=1.0): 乱破 是 崩坏星穹铁道 的智识命途虚数属性角色
                UPDATE [39] (confidence=0.95): 绝区零3.0版本后支持光线追踪和DLSS功能
                DELETE [63]
                
                注意 每一个指令必须在各行独立，且互不影响
                各参数含义：
                subject：填游戏、作品名，不包含特殊字符
                confidence：内容的可信度，范围0.1~1.0
                importance：内容的重要程度，范围0.1~1.0
                decayRate：内容的过期速度，比如内容属于持续更新中的作品时，应当设置较高的值，声明内容过期较快，范围0.1~2.0""");
        ml.add(new MCMessage.Builder()
                .add(new TextField(builder.toString()))
                .build());
        parseResponse = parser.completions(ml, null);
        parseContent = parseResponse.choices[0].message.content;
        applyParseContent(parseContent, existItems);
    }

    public List<Item> quiz(String quiz) throws IOException {
        return quiz(quiz, embedding(List.of(quiz)).get(0));
    }

    /**
     * 查询知识库内容，并尽量自动修补缺失或过期的知识
     * @param quiz 问题
     * @param vector 问题的向量
     * @return 相关知识条目
     * @throws IOException 查询中发生的错误
     */
    public List<Item> quiz(String quiz, List<Float> vector) throws IOException {
        List<Item> items = query(vector, null);
        // Check if the database needs to be updated.
        boolean updateInNeed = true;
        for (var item : items) {
            // 计算知识“有效分数”，考虑衰减
            long ageMillis = System.currentTimeMillis() - item.updatedAt;
            // 将毫秒转换为天数
            float ageDays = ageMillis / (1000F * 60 * 60 * 24);

            // 衰减影响 score，衰减率越大，知识越快过期
            float effectiveScore = item.score - (ageDays * 0.01f * item.decayRate);

            // 判断阈值，既考虑关联度，也考虑是否过期
            if (effectiveScore > 0.8f) {
                updateInNeed = false;  // 已存在相似且有效的知识
                break;
            }
        }
        if (!updateInNeed) {
            return items;
        }
        webCatch(quiz);
        items = query(vector, null); // fetch again
        return items;
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
            this.createdAt = System.currentTimeMillis();
            this.updatedAt = this.createdAt;
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

        @Override
        public String toString() {
            return "Confidence " + this.confidence + " UpdatedAt " + this.updatedAt + " " + this.subject + ": " + this.content + "\n";
        }
    }

    public static class Conditions {
        String subject;
    }

    private static final Pattern NEW_PATTERN =
            Pattern.compile("^NEW\\s*\\((.*?)\\):\\s*(.+)$");
    private static final Pattern UPDATE_PATTERN =
            Pattern.compile("^UPDATE\\s*\\[(\\d+)]\\s*\\((.*?)\\):\\s*(.+)$");
    private static final Pattern DELETE_PATTERN =
            Pattern.compile("^DELETE\\s*\\[(\\d+)]\\s*$");

    private void applyParseContent(String parseContent, List<Item> existItems) {
        List<Item> toInsert = new ArrayList<>();
        Set<UUID> toDelete = new HashSet<>();

        for (String rawLine : parseContent.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            Matcher m;

            m = NEW_PATTERN.matcher(line);
            if (m.matches()) {
                String params = m.group(1);
                String content = m.group(2);
                toInsert.add(buildItemFromParams(params, content));
                continue;
            }

            m = UPDATE_PATTERN.matcher(line);
            if (m.matches()) {
                int idx = Integer.parseInt(m.group(1));
                if (idx < 1 || idx > existItems.size()) continue;

                String params = m.group(2);
                String content = m.group(3);

                Item old = existItems.get(idx - 1);
                Item updated = mergeItem(old, params, content);
                toInsert.add(updated);   // 同 UUID upsert 即可覆盖
                continue;
            }

            m = DELETE_PATTERN.matcher(line);
            if (m.matches()) {
                int idx = Integer.parseInt(m.group(1));
                if (idx < 1 || idx > existItems.size()) continue;
                toDelete.add(existItems.get(idx - 1).id);
            }
        }

        if (!toDelete.isEmpty()) {
            try {
                client.deleteAsync(
                        Points.DeletePoints.newBuilder()
                                .setCollectionName(collection)
                                .setPoints(Points.PointsSelector.newBuilder()
                                        .setPoints(Points.PointsIdsList.newBuilder()
                                                .addAllIds(toDelete.stream()
                                                        .map(id -> id(UUID.fromString(id.toString())))
                                                        .toList())
                                                .build())
                                        .build())
                                .build()
                ).get();
            } catch (InterruptedException | ExecutionException e) {
                logger.error("未能正确删除知识条目", e);
            }
        }

        if (!toInsert.isEmpty()) {
            insert(toInsert);
        }
    }

    private Item buildItemFromParams(String params, String content) {
        Item item = new Item();
        item.content = content.trim();
        item.subject = findStringParam(params, "subject", "unknown");
        item.source = findStringParam(params, "source", "web");
        item.confidence = findFloatParam(params, "confidence", 0.8f);
        item.importance = findFloatParam(params, "importance", 0.5f);
        item.decayRate = findFloatParam(params, "decayRate", 1f);
        item.score = findFloatParam(params, "score", 0.8f);
        long now = System.currentTimeMillis();
        item.createdAt = now;
        item.updatedAt = now;
        return item;
    }

    private Item mergeItem(Item old, String params, String content) {
        old.content = content.trim();
        String subject = findStringParam(params, "subject", null);
        if (subject != null) old.subject = subject;

        String source = findStringParam(params, "source", null);
        if (source != null) old.source = source;

        old.confidence = findFloatParam(params, "confidence", old.confidence);
        old.importance = findFloatParam(params, "importance", old.importance);
        old.decayRate = findFloatParam(params, "decayRate", old.decayRate);
        old.score = findFloatParam(params, "score", old.score);
        old.updatedAt = System.currentTimeMillis();
        return old;
    }

    private String findStringParam(String params, String key, String defaultValue) {
        Pattern p = Pattern.compile(key + "\\s*=\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(params);
        return m.find() ? m.group(1) : defaultValue;
    }

    private float findFloatParam(String params, String key, float defaultValue) {
        Pattern p = Pattern.compile(key + "\\s*=\\s*([0-9.]+)");
        Matcher m = p.matcher(params);
        return m.find() ? Float.parseFloat(m.group(1)) : defaultValue;
    }
}