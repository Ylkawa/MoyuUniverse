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
import com.zaxxer.hikari.HikariDataSource;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;

public class KnowledgeStore {
    private final QdrantClient client;
    private final HikariDataSource dataSource;
    private final Embedding embedding;
    private final LLMProvider llmProvider;
    private final String collection;
    private final String vectorName;
    private final String documentsTable;
    private final int dimension;
    private final int queryCount;
    private final int candidateLimit;
    private final int chunkTokenThreshold;
    private final int embeddingBatchSize;
    private final int defaultTtlDays;
    private final String llmModel;
    private final Logger logger = LoggerFactory.getLogger(KnowledgeStore.class);
    private final BlockingQueue<IndexTask> indexQueue = new LinkedBlockingQueue<>();

    public KnowledgeStore(Config.Qdrant qdrantCfg, Config.KnowledgeStore ksCfg, HikariDataSource dataSource) throws IOException {
        this.dataSource = dataSource;
        try {
            embedding = (Embedding) Universe.Providers.get(ksCfg.embeddingProvider);
        } catch (ClassCastException e) {
            throw new IOException("Embedding provider not found: " + ksCfg.embeddingProvider);
        }
        try {
            llmProvider = (LLMProvider) Universe.Providers.get(ksCfg.llmProvider);
        } catch (ClassCastException e) {
            throw new IOException("LLM provider not found: " + ksCfg.llmProvider);
        }
        llmModel = ksCfg.llmModel;
        dimension = ksCfg.dimension;
        queryCount = ksCfg.queryCount;
        candidateLimit = ksCfg.candidateLimit;
        chunkTokenThreshold = ksCfg.chunkTokenThreshold;
        embeddingBatchSize = ksCfg.embeddingBatchSize;
        defaultTtlDays = ksCfg.defaultTtlDays;

        try {
            var builder = QdrantGrpcClient.newBuilder(qdrantCfg.address, qdrantCfg.port, qdrantCfg.encryptedConnection);
            if (qdrantCfg.secretKey != null) builder.withApiKey(qdrantCfg.secretKey);
            client = new QdrantClient(builder.build());
            collection = ksCfg.collection;
            vectorName = ksCfg.vectorName;
            documentsTable = ksCfg.documentsTable;
            initCollection();
            initTable();
        } catch (InterruptedException | ExecutionException | SQLException e) {
            throw new IOException(e);
        }

        new Thread(() -> {
            while (true) {
                try {
                    IndexTask task = indexQueue.take();
                    indexDocumentInternal(task.url, task.title, task.content);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    logger.error("Async index failed", e);
                }
            }
        }, "KnowledgeStore-AsyncIndex").start();
    }

    private void initCollection() throws InterruptedException, ExecutionException {
        if (client.collectionExistsAsync(collection).get()) return;
        Map<String, Collections.VectorParams> vectorParams = Map.of(
                vectorName,
                Collections.VectorParams.newBuilder()
                        .setSize(dimension)
                        .setDistance(Collections.Distance.Cosine)
                        .build()
        );
        logger.info("Creating KnowledgeStore collection: {}", collection);
        client.createCollectionAsync(collection, vectorParams).get();
        client.createPayloadIndexAsync(collection, "document_id", Collections.PayloadSchemaType.Integer, null, null, null, null).get();
        client.createPayloadIndexAsync(collection, "ttl", Collections.PayloadSchemaType.Integer, null, null, null, null).get();
        logger.info("KnowledgeStore collection created: {}", collection);
    }

    private void initTable() throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    url TEXT NOT NULL,
                    url_hash CHAR(64) NOT NULL UNIQUE,
                    title VARCHAR(512),
                    content LONGTEXT,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(documentsTable);
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
        logger.info("Documents table ready: {}", documentsTable);
    }

    public void indexDocument(String url, String title, String content) {
        indexQueue.offer(new IndexTask(url, title, content));
    }

    public void indexDocumentSync(String url, String title, String content) throws IOException {
        indexDocumentInternal(url, title, content);
    }

    private void indexDocumentInternal(String url, String title, String content) throws IOException {
        long start = System.currentTimeMillis();
        logger.info("Indexing document: url={}, contentLen={}", url, content.length());

        long documentId = upsertDocument(url, title, content);
        if (documentId < 0) {
            logger.error("Failed to upsert document for url={}", url);
            return;
        }

        List<String> chunks = chunkContent(content);
        logger.info("Document split into {} chunk(s)", chunks.size());

        List<String> allQueries = new ArrayList<>();
        List<String> allConclusions = new ArrayList<>();
        List<Float> allConfidences = new ArrayList<>();
        List<Integer> allTtls = new ArrayList<>();

        for (String chunk : chunks) {
            List<QueryConclusion> qcs = generateQueriesWithConclusions(chunk);
            if (qcs.isEmpty()) {
                logger.warn("Query generation returned empty for a chunk, skipping");
                continue;
            }
            for (QueryConclusion qc : qcs) {
                allQueries.add(qc.query);
                allConclusions.add(qc.conclusion);
                allConfidences.add(qc.confidence);
                allTtls.add(qc.ttlDays);
            }
        }

        if (allQueries.isEmpty()) {
            logger.warn("No queries generated, aborting index for url={}", url);
            return;
        }

        logger.info("Generated {} queries total, computing embeddings in batches", allQueries.size());
        List<List<Float>> vectors;
        try {
            vectors = batchEmbed(allQueries);
        } catch (Exception e) {
            logger.error("Embedding failed for url={}", url, e);
            return;
        }
        if (vectors.size() != allQueries.size()) {
            logger.error("Embedding count mismatch: expected={}, got={}", allQueries.size(), vectors.size());
            return;
        }

        deletePointsByDocumentId(documentId);

        long now = System.currentTimeMillis();
        long baseTtl = now + (long) defaultTtlDays * 24 * 60 * 60 * 1000;
        List<Points.PointStruct> newPoints = new ArrayList<>();
        for (int i = 0; i < allQueries.size(); i++) {
            long ttl = now + (long) allTtls.get(i) * 24 * 60 * 60 * 1000;
            Points.PointStruct point = Points.PointStruct.newBuilder()
                    .setId(Common.PointId.newBuilder().setUuid(UUID.randomUUID().toString()).build())
                    .setVectors(namedVectors(Map.of(vectorName, vector(vectors.get(i)))))
                    .putPayload("document_id", JsonWithInt.Value.newBuilder().setIntegerValue(documentId).build())
                    .putPayload("query", JsonWithInt.Value.newBuilder().setStringValue(allQueries.get(i)).build())
                    .putPayload("conclusion", JsonWithInt.Value.newBuilder().setStringValue(allConclusions.get(i)).build())
                    .putPayload("confidence", JsonWithInt.Value.newBuilder().setDoubleValue(allConfidences.get(i)).build())
                    .putPayload("ttl", JsonWithInt.Value.newBuilder().setIntegerValue(ttl).build())
                    .putPayload("created_at", JsonWithInt.Value.newBuilder().setIntegerValue(now).build())
                    .build();
            newPoints.add(point);
        }

        try {
            client.upsertAsync(collection, newPoints).get();
            logger.info("Upserted {} points for document_id={}, url={}", newPoints.size(), documentId, url);
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Upsert failed for document_id={}, url={}", documentId, url, e);
            return;
        }

        logger.info("Index complete: url={}, documentId={}, chunks={}, queries={}, elapsed={}ms",
                url, documentId, chunks.size(), allQueries.size(), System.currentTimeMillis() - start);
    }

    private long upsertDocument(String url, String title, String content) {
        long now = System.currentTimeMillis();
        String urlHash = sha256(url);
        String sql = """
                INSERT INTO %s (url, url_hash, title, content, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE title=VALUES(title), content=VALUES(content), updated_at=VALUES(updated_at)
                """.formatted(documentsTable);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, url);
            pstmt.setString(2, urlHash);
            pstmt.setString(3, title);
            pstmt.setString(4, content);
            pstmt.setLong(5, now);
            pstmt.setLong(6, now);
            pstmt.executeUpdate();
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
            try (PreparedStatement select = conn.prepareStatement("SELECT id FROM " + documentsTable + " WHERE url_hash = ?")) {
                select.setString(1, urlHash);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to upsert document for url={}", url, e);
        }
        return -1;
    }

    public String getDocumentContent(long documentId) {
        String sql = "SELECT content FROM " + documentsTable + " WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, documentId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            logger.error("Failed to get document content for id={}", documentId, e);
        }
        return null;
    }

    public String getDocumentTitle(long documentId) {
        String sql = "SELECT title FROM " + documentsTable + " WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, documentId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            logger.error("Failed to get document title for id={}", documentId, e);
        }
        return null;
    }

    public String getDocumentUrl(long documentId) {
        String sql = "SELECT url FROM " + documentsTable + " WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, documentId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            logger.error("Failed to get document url for id={}", documentId, e);
        }
        return null;
    }

    private void deletePointsByDocumentId(long documentId) {
        try {
            Common.Condition condition = Common.Condition.newBuilder()
                    .setField(Common.FieldCondition.newBuilder()
                            .setKey("document_id")
                            .setMatch(Common.Match.newBuilder().setInteger(documentId).build())
                            .build())
                    .build();
            Common.Filter filter = Common.Filter.newBuilder().addMust(condition).build();
            client.deleteAsync(collection, filter).get();
            logger.debug("Deleted points for document_id={}", documentId);
        } catch (Exception e) {
            logger.error("Failed to delete points for document_id={}", documentId, e);
        }
    }

    public List<KnowledgeResult> search(String question) throws IOException {
        long start = System.currentTimeMillis();
        List<Float> vector = batchEmbed(List.of(question)).get(0);

        long now = System.currentTimeMillis();
        Common.Condition ttlCondition = Common.Condition.newBuilder()
                .setField(Common.FieldCondition.newBuilder()
                        .setKey("ttl")
                        .setRange(Common.Range.newBuilder().setGte(now).build())
                        .build())
                .build();
        Common.Filter filter = Common.Filter.newBuilder().addMust(ttlCondition).build();

        List<Points.ScoredPoint> result;
        try {
            result = client.searchAsync(
                    Points.SearchPoints.newBuilder()
                            .setCollectionName(collection)
                            .addAllVector(vector)
                            .setVectorName(vectorName)
                            .setLimit(candidateLimit)
                            .setFilter(filter)
                            .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                            .build()
            ).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException(e);
        }

        Map<Long, KnowledgeResult> deduped = new LinkedHashMap<>();
        for (var point : result) {
            var payload = point.getPayloadMap();
            long documentId = payload.get("document_id").getIntegerValue();
            String query = payload.getOrDefault("query", JsonWithInt.Value.getDefaultInstance()).getStringValue();
            String conclusion = payload.getOrDefault("conclusion", JsonWithInt.Value.getDefaultInstance()).getStringValue();
            float confidence = (float) payload.getOrDefault("confidence", JsonWithInt.Value.getDefaultInstance()).getDoubleValue();
            long ttl = payload.getOrDefault("ttl", JsonWithInt.Value.getDefaultInstance()).getIntegerValue();

            KnowledgeResult kr = deduped.get(documentId);
            if (kr == null) {
                kr = new KnowledgeResult();
                kr.documentId = documentId;
                kr.url = getDocumentUrl(documentId);
                kr.title = getDocumentTitle(documentId);
                kr.content = getDocumentContent(documentId);
                kr.bestScore = point.getScore();
                kr.bestQuery = query;
                kr.bestConclusion = conclusion;
                kr.bestConfidence = confidence;
                kr.matchedQueries = new ArrayList<>();
                kr.matchedQueries.add(query);
                deduped.put(documentId, kr);
            } else {
                if (point.getScore() > kr.bestScore) {
                    kr.bestScore = point.getScore();
                    kr.bestQuery = query;
                    kr.bestConclusion = conclusion;
                    kr.bestConfidence = confidence;
                }
                kr.matchedQueries.add(query);
            }
        }

        List<KnowledgeResult> results = new ArrayList<>(deduped.values());
        results.sort((a, b) -> Float.compare(b.bestScore, a.bestScore));

        logger.debug("Search: candidates={}, deduped={}, elapsed={}ms",
                result.size(), results.size(), System.currentTimeMillis() - start);
        return results;
    }

    public boolean hasDocument(String url) {
        String sql = "SELECT COUNT(*) FROM " + documentsTable + " WHERE url_hash = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sha256(url));
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            logger.error("hasDocument check failed for url={}", url, e);
        }
        return false;
    }

    public Long getDocumentIdByUrl(String url) {
        String sql = "SELECT id FROM " + documentsTable + " WHERE url_hash = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sha256(url));
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            logger.error("getDocumentIdByUrl failed for url={}", url, e);
        }
        return null;
    }

    private static String sha256(String value) {
        return sha256(value, null);
    }

    private static String sha256(String value, Logger log) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            if (log != null) log.error("SHA-256 failed", e);
            return value;
        }
    }

    private List<QueryConclusion> generateQueriesWithConclusions(String content) throws IOException {
        Assistant assistant = new Assistant(llmProvider, llmModel);
        MessageList ml = new MessageList();
        ml.add(MCMessage.Builder()
                .add(new TextField(Prompt.queryGenerator.formatted(queryCount)))
                .build());
        ml.add(MCMessage.Builder()
                .add(new TextField(content))
                .build());

        CompletionsResponse resp = assistant.completions(ml, null);
        String output = resp.choices[0].message.content;
        List<QueryConclusion> results = new ArrayList<>();
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\|", 4);
            if (parts.length >= 4) {
                try {
                    QueryConclusion qc = new QueryConclusion();
                    qc.query = parts[0].trim();
                    qc.conclusion = parts[1].trim();
                    qc.confidence = Float.parseFloat(parts[2].trim());
                    qc.ttlDays = Integer.parseInt(parts[3].trim());
                    results.add(qc);
                } catch (NumberFormatException e) {
                    logger.warn("Failed to parse query line: {}", trimmed);
                }
            }
        }
        logger.debug("Generated {} query-conclusion pairs for content (len={})", results.size(), content.length());
        return results;
    }

    private List<String> chunkContent(String content) {
        int estimatedTokens = content.length() / 2;
        if (estimatedTokens < chunkTokenThreshold) {
            return List.of(content);
        }

        List<String> chunks = new ArrayList<>();
        String[] paragraphs = content.split("\n\n+");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;

            if (current.length() == 0) {
                current.append(trimmed);
            } else if (current.length() + trimmed.length() < 200) {
                current.append("\n\n").append(trimmed);
            } else {
                chunks.add(current.toString());
                current = new StringBuilder(trimmed);
            }

            if (current.length() > 4000) {
                List<String> subChunks = splitLongText(current.toString());
                chunks.addAll(subChunks);
                current = new StringBuilder();
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }

        return chunks;
    }

    private List<String> splitLongText(String text) {
        List<String> result = new ArrayList<>();
        String[] sentences = text.split("(?<=[。！？.!?\n])");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence.trim().isEmpty()) continue;
            if (current.length() + sentence.length() > 4000 && current.length() > 0) {
                result.add(current.toString());
                current = new StringBuilder();
            }
            current.append(sentence);
        }
        if (current.length() > 0) result.add(current.toString());
        return result;
    }

    private List<List<Float>> batchEmbed(List<String> texts) throws IOException {
        List<List<Float>> allVectors = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += embeddingBatchSize) {
            List<String> batch = texts.subList(i, Math.min(i + embeddingBatchSize, texts.size()));
            EmbeddingRequest request = new EmbeddingRequest();
            for (String text : batch) {
                MFChain mfc = new MFChain();
                mfc.add(new TextField(text));
                request.message.add(mfc);
            }
            EmbeddingResponse response = embedding.embedding(request);
            for (var data : response.data) {
                List<Float> vec = new ArrayList<>();
                for (double v : data.embedding) {
                    vec.add((float) v);
                }
                allVectors.add(vec);
            }
        }
        return allVectors;
    }

    public static class KnowledgeResult {
        public long documentId;
        public String url;
        public String title;
        public String content;
        public float bestScore;
        public String bestQuery;
        public String bestConclusion;
        public float bestConfidence;
        public List<String> matchedQueries;

        @Override
        public String toString() {
            return "[" + title + "](" + url + ") score=" + bestScore + " confidence=" + bestConfidence +
                    "\nQ: " + bestQuery + "\nA: " + bestConclusion + "\n" + content;
        }
    }

    private static class QueryConclusion {
        String query;
        String conclusion;
        float confidence;
        int ttlDays;
    }

    private static class IndexTask {
        final String url;
        final String title;
        final String content;

        IndexTask(String url, String title, String content) {
            this.url = url;
            this.title = title;
            this.content = content;
        }
    }

    private static class Prompt {
        static String queryGenerator = """
                        你是一个知识库查询生成器。
                        
                        根据给定内容，生成 %d 个用户可能会提出的、能够被知识库准确检索和回答的自然语言问题，以及每个问题对应的简洁结论。
                        
                        【核心目标】
                        
                        生成的问题必须满足：
                        
                        * 问题本身脱离原文后仍然能够独立理解
                        * 问题能够唯一定位到给定内容中的明确主体
                        * 问题中的时间、版本、模式、平台、地区等范围必须明确
                        * 结论必须能够直接回答问题
                        * 问题和结论都只能使用给定内容中明确存在的事实
                        * 宁可少生成，也绝对不要为了凑够 %d 条而生成信息不足的问题
                        
                        【一、主体必须明确且唯一】
                        
                        每个问题都必须明确写出具体主体。
                        
                        “主体”包括但不限于：
                        
                        * 游戏：具体游戏名称
                        * 产品：具体产品名称
                        * 软件：具体软件名称
                        * 系统：具体系统名称
                        * 角色：具体角色名称
                        * 公司：具体公司名称
                        * 事件：具体事件名称
                        * 文档：具体文档名称
                        * 报告：具体报告名称
                        * 表格：具体表格名称或能够唯一定位该表格的明确名称
                        
                        禁止使用无法唯一定位主体的泛称，例如：
                        
                        “这款游戏”
                        “该游戏”
                        “这个游戏”
                        “该产品”
                        “这款产品”
                        “该系统”
                        “这个系统”
                        “该软件”
                        “这个功能”
                        “该功能”
                        “报价表”
                        “报告”
                        “文档”
                        “官方页面”
                        “相关产品”
                        “相关系统”
                        
                        如果原文只出现“报价表”“价格表”“报告”等泛称，而没有明确说明该表格/报告属于什么主体，或者无法唯一确定其名称，则禁止围绕该主体生成问题。
                        
                        例如原文只有：
                        
                        “记者获得的报价表中，产品价格从230元到3700元……”
                        
                        禁止生成：
                        
                        “报价表的价格区间是多少？”
                        “报价表的价格区间和材质标注说明了什么？”
                        
                        因为“报价表”无法作为唯一、明确的主体。
                        
                        只有当原文明确给出例如：
                        
                        “某某产品2026年报价表”
                        “某某公司的某某产品报价表”
                        
                        才能围绕该具体主体生成问题。
                        
                        【二、适用范围必须明确】
                        
                        每个问题必须明确写出适用范围。
                        
                        适用范围包括但不限于：
                        
                        * 具体版本号
                        * 游戏模式
                        * 平台
                        * 地区
                        * 时间段
                        * 产品型号
                        * 产品版本
                        * 活动名称
                        * 文档/报告对应的时间
                        * 明确的事件阶段
                        
                        如果给定内容无法确定具体适用范围，则不要生成对应问题。
                        
                        禁止自行补充、猜测或推断版本号、日期、平台、模式、地区、产品型号等信息。
                        
                        禁止使用以下模糊范围：
                        
                        “新版本”
                        “当前版本”
                        “最新版本”
                        “以前”
                        “之后”
                        “后来”
                        “最近”
                        “近期”
                        “目前”
                        “现在”
                        “当时”
                        “那时候”
                        “未来”
                        “几个月后”
                        “几个月前”
                        “几年后”
                        “几年以前”
                        “上线后”
                        “更新后”
                        “活动期间”
                        
                        除非这些词在给定内容中本身就是一个明确且不可替代的专有时间描述，否则一律禁止。
                        
                        【三、时间必须使用绝对时间】
                        
                        凡是涉及时间的问题、结论或时间范围，必须优先使用给定内容中的绝对时间。
                        
                        允许：
                        
                        “2026年8月”
                        “2026年8月30日”
                        “2026年第三季度”
                        “2025年1月至2025年3月”
                        “截至2026年8月”
                        “2024年版本”
                        
                        禁止：
                        
                        “几个月后”
                        “几个月前”
                        “半年后”
                        “半年以前”
                        “一年后”
                        “最近”
                        “近期”
                        “之后”
                        “后来”
                        “此前”
                        “当时”
                        “现在”
                        “目前”
                        “未来”
                        “下一年”
                        “下个月”
                        “明年”
                        “去年”
                        
                        如果原文使用了相对时间，只有在能够根据原文明确换算成绝对日期时，才允许转换成绝对日期。
                        
                        例如：
                        
                        原文明确写出：
                        “2026年5月发布，三个月后进行调整。”
                        
                        可以生成：
                        
                        “2026年8月调整了什么？”
                        
                        不能生成：
                        
                        “发布几个月后进行了什么调整？”
                        
                        如果无法确定绝对日期，则不要生成涉及该相对时间的问题。
                        
                        【四、禁止隐含主体】
                        
                        问题不能依赖用户已经阅读过原文。
                        
                        禁止：
                        
                        “它多少钱？”
                        “它什么时候发布？”
                        “这个功能怎么用？”
                        “该产品有哪些特点？”
                        “这个价格合理吗？”
                        “它支持什么材质？”
                        “这个版本有什么变化？”
                        
                        必须改成包含明确主体的形式，例如：
                        
                        “某某产品2026年报价表中的产品价格范围是多少？”
                        
                        但如果“某某产品”本身并未在给定内容中明确出现，则不要生成。
                        
                        【五、问题必须能够独立检索】
                        
                        把问题单独复制到一个全新的聊天窗口，在完全不知道原文的情况下，也必须能够理解问题在问什么。
                        
                        执行以下测试：
                        
                        删除原文，只保留问题。
                        
                        如果一个不知道原文的人仍然无法确定：
                        
                        * 在问哪个主体
                        * 在问哪个版本
                        * 在问哪个时间
                        * 在问哪个平台/模式/地区
                        * “这个/该/它/上述/前述”等指代具体是什么
                        
                        则该问题禁止生成。
                        
                        【六、问题必须有明确答案】
                        
                        只有给定内容明确提供答案的问题才能生成。
                        
                        禁止根据常识、推测、行业规律或模型自身知识补充答案。
                        
                        禁止生成：
                        
                        “为什么这个产品价格合理？”
                        “这个产品质量怎么样？”
                        “这种材料安全吗？”
                        “这个方案是否值得购买？”
                        
                        除非给定内容明确提供了对应事实和结论。
                        
                        如果内容只明确提供：
                        
                        “产品价格从230元到3700元不等。”
                        
                        可以生成：
                        
                        “某某产品2026年报价表中的产品价格范围是多少？”
                        
                        不能生成：
                        
                        “某某产品2026年报价表中的价格为什么是230元到3700元？”
                        
                        因为原文没有明确解释价格形成原因。
                        
                        【七、结论必须严格对应问题】
                        
                        结论必须直接回答问题，而不是泛泛总结原文。
                        
                        结论开头必须重新明确写出问题中的主体和适用范围。
                        
                        问题：
                        
                        “某某产品2026年报价表中的产品价格范围是多少？”
                        
                        正确：
                        
                        “某某产品2026年报价表中的产品价格从230元到3700元不等。”
                        
                        错误：
                        
                        “价格从230元到3700元不等。”
                        
                        因为结论缺少主体和适用范围。
                        
                        【八、结论必须包含具体事实】
                        
                        每条结论必须包含至少一个能够直接从给定内容提取的具体事实，包括：
                        
                        * 数字
                        * 日期
                        * 时间
                        * 版本号
                        * 产品名称
                        * 游戏名称
                        * 角色名称
                        * 型号
                        * 材质名称
                        * 平台名称
                        * 公司名称
                        * 明确步骤
                        * 明确功能名称
                        
                        不能输出没有实质信息的结论。
                        
                        禁止：
                        
                        “该产品价格存在较大差异。”
                        “这个版本进行了相关调整。”
                        “该系统支持相关功能。”
                        “具体情况比较复杂。”
                        
                        【九、禁止模型自行概括主体】
                        
                        不要把原文中的描述自行提升成主体。
                        
                        例如原文：
                        
                        “记者获得的报价表中，产品价格从230元到3700元不等……”
                        
                        不能自行创造：
                        
                        “记者报价表”
                        “某某产品报价表”
                        “该产品报价表”
                        
                        除非原文明确给出了对应名称。
                        
                        同样，不能因为内容讨论的是某个产品类别，就自行认为该类别名称是具体主体。
                        
                        【十、别名和口语化表达的限制】
                        
                        可以使用常见别名、简称和口语化表达，但前提是给定内容明确能够确认这些名称指向同一个主体。
                        
                        例如原文明确说明：
                        
                        “原神（Genshin Impact）”
                        
                        可以生成：
                        
                        “原神3.2版本……”
                        
                        但如果原文没有明确说明两个名称之间的对应关系，不得自行建立别名关系。
                        
                        【十一、有效天数】
                        
                        有效天数必须根据给定内容的时效性设置：
                        
                        * 永久稳定事实：3650
                        * 长期稳定事实：730
                        * 一般稳定事实：365
                        * 可能随版本变化：180
                        * 活动、价格、政策等较容易变化：90
                        * 高频变化的信息：30
                        * 极短期信息：7
                        
                        不要因为知识本身看起来重要而随意设置较长有效期。
                        
                        【十二、置信度】
                        
                        置信度表示“给定内容是否明确支持这个问题和结论”。
                        
                        * 0.95-1.00：内容直接明确给出答案
                        * 0.90-0.94：内容明确支持，但需要非常轻微的语言转换
                        * 0.80-0.89：内容基本支持，但存在一定解释空间
                        * 低于0.80：不要生成
                        
                        因此，宁可少生成，也不要为了数量生成低置信度条目。
                        
                        【十三、生成前必须逐条执行硬性检查】
                        
                        生成每一条之前，必须依次检查：
                        
                        1. 主体是否明确？
                        2. 主体是否能够唯一定位？
                        3. 适用范围是否明确？
                        4. 是否存在“它/这个/该/上述/前述”等指代？
                        5. 是否存在“最近/之后/几个月后”等相对时间？
                        6. 如果存在时间，是否能够转换成绝对时间？
                        7. 给定内容是否明确回答这个问题？
                        8. 结论是否直接回答问题？
                        9. 结论是否重新包含主体和适用范围？
                        10. 结论是否包含具体事实？
                        11. 是否需要模型自身知识进行补充？
                        12. 是否存在任何无法从原文确认的信息？
                        
                        只要其中任意一项不通过，就直接丢弃该条，不得输出。
                        
                        【十四、最重要的数量原则】
                        
                        “%d个”是最大目标，不是必须完成的数量。
                        
                        如果给定内容只有3个问题满足全部规则，即使要求生成10个，也只能输出3个。
                        
                        禁止：
                        
                        * 为了凑数量降低标准
                        * 为了覆盖角度而创造没有答案的问题
                        * 为了生成更多问题而泛化主体
                        * 为了生成更多问题而使用模糊时间
                        * 为了生成更多问题而使用上下文指代
                        
                        【十五、最终输出格式】
                        
                        只输出满足全部规则的问题。
                        
                        每行一组：
                        
                        问题|结论|置信度|有效天数
                        
                        不要编号。
                        不要markdown。
                        不要解释。
                        不要输出被跳过的条目。
                        不要输出任何其他内容。
                        
                        【最终示例】
                        
                        给定内容：
                        
                        “原神3.2版本于2022年11月2日更新，五星草系角色纳西妲通过角色活动祈愿获得。”
                        
                        正确：
                        
                        原神3.2版本什么时候更新|原神3.2版本于2022年11月2日更新|0.99|3650
                        原神3.2版本的纳西妲怎么获得|原神3.2版本的五星草系角色纳西妲通过角色活动祈愿获得|0.99|3650
                        
                        错误：
                        
                        这个版本什么时候更新|原神3.2版本于2022年11月2日更新|0.99|3650
                        原神新版本什么时候更新|原神3.2版本于2022年11月2日更新|0.99|3650
                        纳西妲怎么获得|原神3.2版本的五星草系角色纳西妲通过角色活动祈愿获得|0.99|3650
                        
                        因为错误示例中的问题缺少明确主体或适用范围。
                        
                        【再次强调】
                        
                        如果无法确认“这是哪个主体”，不要生成。
                        
                        如果无法确认“适用于哪个版本/时间/模式/平台”，不要生成。
                        
                        如果时间只能写成“几个月后”“最近”“之后”等相对表达，不要生成，除非能够根据原文转换为明确的绝对时间。
                        
                        如果问题需要依赖原文上下文才能理解，不要生成。
                        
                        如果答案不是给定内容中的明确事实，不要生成。
                        
                        如果只是为了凑够 %d 条才需要降低上述标准，不要凑数。
                        """;
    }
}
