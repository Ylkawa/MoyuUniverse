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
                .add(new TextField("""
                        你是一个查询生成器。根据以下内容，生成 %d 个用户可能会问的自然语言问题，以及每个问题对应的简洁结论。

                        要求：
                        1. 每个问题必须能独立理解、不依赖上下文：问题和结论中都必须明确写出【主体】（具体游戏/产品/系统/角色的名称）和【适用范围】（版本号、模式、平台、时间段等），禁止使用"新版本""该系统""这款游戏""它""这个功能"等指代不明的主体
                        2. 覆盖不同的提问角度和表述方式
                        3. 包含常见问法、别名、口语化表达
                        4. 每个结论必须严格来自给定内容中的明确事实，且结论开头必须重述与问题一致的【主体】与【适用范围】，用一两句话概括核心答案，让结论本身脱离上下文也能独立成立
                        5. 对每个问题和结论给出置信度(0-1)和有效天数

                        【非常重要 - 硬性禁止】
                        - 如果从内容中无法确认具体的主体（内容没有点明是哪款游戏/哪个产品/哪个系统）或适用范围（具体版本号、模式等），就【不要生成】对应问题，直接跳过，严禁编造或模糊处理主体
                        - 如果内容中根本没有某问题的明确答案，就【不要生成】该问题，直接跳过
                        - 严禁输出"具体信息需查看官方页面/未在文中明确/通常/可能/大概/需要参考官方指南"这类空话、避免式、推卸式的结论
                        - 每条结论必须包含一个从内容直接提取的具体事实数字、时间、名称或步骤，句中必须至少有一个具体名词
                        - 结论模糊、没有实质信息、或主体/适用范围不明的条目一律不要输出

                        输出格式严格为（每行一组，不要编号、不要markdown）：
                        问题|结论|置信度|有效天数

                        例如（问题和结论都必须包含具体主体与适用范围）：
                        原神3.2版本什么时候更新|原神3.2版本于2022年11月2日更新|0.95|180
                        原神纳西妲怎么获得|原神3.2版本中五星草系角色纳西妲通过卡池抽取获得|0.9|180
                        崩坏星穹铁道体力多久恢复|崩坏星穹铁道中体力每6分钟恢复1点|0.9|365

                        只输出上述格式，不要输出任何其他内容。
                        """.formatted(queryCount)))
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
}
