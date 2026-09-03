package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageChannel.MessageList;
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
        ChatAssistant assistant = new ChatAssistant(llmProvider, llmModel);
        MessageList ml = new MessageList();
        ml.add(MCMessage.Builder()
                .add(new TextField(Prompt.queryGenerator))
                .build());
        ml.add(MCMessage.Builder()
                .add(new TextField(content))
                .build());

        assistant.setChatContext(ChatContext.from(ml));
        CompletionsResponse resp = assistant.completions(null);
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
                
                请根据给定内容，提取其中最适合用于知识库检索的高价值信息，并将其转换为若干组“自然语言问题 + 简洁结论”。
                
                核心目标：
                - 问题能够脱离原文独立理解。
                - 问题能够明确定位到具体主体。
                - 问题涉及版本、时间、平台、模式、地区、型号等范围时，必须明确写出。
                - 结论必须直接回答问题，并包含原文中的具体事实。
                - 问题和结论只能使用给定内容中的事实，不得依赖常识、推测或模型自身知识。
                - 只生成真正有检索价值的问题，不要为了增加数量而生成低价值或重复的问题。
                
                【主体】
                问题必须明确写出具体主体，例如游戏、产品、软件、系统、角色、公司、事件、文档、报告等。
                
                禁止使用无法独立确定主体的表达：
                “这个游戏”“该产品”“这个功能”“它”“上述内容”“报价表”“报告”等。
                
                如果原文没有提供足以唯一确定主体的信息，则不要围绕该信息生成问题。
                不要自行创造主体名称，也不要把产品类别、描述或泛称提升为具体主体。
                
                【范围与时间】
                如果原文明确提供了版本、日期、时间段、平台、模式、地区、型号、活动等范围，应在问题中保留这些信息。
                
                时间优先使用原文中的绝对时间，例如：
                “2026年8月30日”“2026年第三季度”“2025年1月至3月”。
                
                不要使用“最近”“目前”“之后”“当时”“几个月后”等模糊或相对时间。
                如果相对时间能够根据原文准确换算为绝对时间，可以进行转换；否则不要生成相关问题。
                
                【问题质量】
                一个问题单独拿出来时，即使完全不知道原文，也应该能够理解它在询问什么。
                
                优先生成能够直接从内容中回答的问题，例如：
                - 某个主体在特定版本中的变化
                - 某个产品在特定时间的价格
                - 某个角色的获取方式
                - 某个活动的举办时间和地点
                - 某个功能的具体使用方式
                - 某个事件中的明确事实
                
                不要生成需要评价、推测、解释原因或调用外部知识的问题，除非原文明确提供了对应答案。
                
                【结论】
                结论必须直接回答问题。
                必须重新写出主体以及必要的版本、时间、平台等范围，不能只写“是”“有”“价格为XXX”等脱离上下文的答案。
                
                结论应尽可能简洁，但必须保留回答问题所需的关键事实，例如名称、日期、数字、版本、型号、平台、步骤等。
                
                【有效天数】
                根据信息的时效性设置有效天数：
                - 永久稳定事实：3650
                - 长期稳定事实：730
                - 一般稳定事实：365
                - 可能随版本变化：180
                - 活动、价格、政策等：90
                - 高频变化信息：30
                - 极短期信息：7
                
                【置信度】
                表示原文对该问题和结论的支持程度：
                - 0.95-1.00：原文直接明确给出答案
                - 0.90-0.94：仅进行了轻微语言转换
                - 0.80-0.89：存在一定解释空间
                - 低于0.80：不要生成
                
                【生成原则】
                不需要凑够固定数量。
                
                优先保留：
                1. 信息明确
                2. 主体明确
                3. 范围明确
                4. 答案具体
                5. 对用户检索有实际价值
                
                如果内容只能产生少量高质量问题，就只生成这些问题。
                如果内容没有足够的信息生成高质量问题，则不要输出。
                
                避免：
                - 重复表达同一事实
                - 仅改变问法的重复问题
                - 过于宽泛的问题
                - 依赖上下文的问题
                - 无法从原文直接回答的问题
                - 模型自行补充的信息
                - 为了增加数量而降低标准
                
                【最终检查】
                输出前逐条确认：
                - 主体明确且唯一
                - 问题可以独立理解
                - 必要的版本、时间、平台、模式、地区等范围明确
                - 没有模糊指代
                - 没有无法确定的相对时间
                - 答案确实存在于原文
                - 结论直接回答问题
                - 结论包含必要的主体和范围
                - 没有使用原文之外的信息
                - 问题具有实际检索价值
                
                任意一项不满足就删除该条。
                
                【输出格式】
                每行一组：
                
                问题|结论|置信度|有效天数
                
                不要编号。
                不要 Markdown。
                不要解释。
                不要输出被删除的条目。
                不要输出其他内容。
                """;
    }
}
