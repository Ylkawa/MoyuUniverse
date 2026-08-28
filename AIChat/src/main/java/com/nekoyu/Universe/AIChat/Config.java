package com.nekoyu.Universe.AIChat;

import java.util.List;

public class Config {
    String PromptFirst = "";
    String PromptLast = "";
    String ProviderId = null;

    SQLConfig SQLConfig;
    Qdrant Qdrant = null;
    Annotator Annotator = null;
    Memory Memory = null;
    KnowledgeStore KnowledgeStore = null;
    Librarian Librarian = null;

    public static class SQLConfig {
        String url;
        String user;
        String password;
    }

    public static class Qdrant {
        String address = "127.0.0.1";
        int port = 6334;
        boolean encryptedConnection = true;
        String secretKey = null;
        String provider = null;
    }

    public static class Annotator {
        String provider = null;
        String embeddingProvider = null;
        String model = null;
        int dimension = 0;
        boolean enable_thinking = false;
    }

    public static class Memory {
        String collection = "moyu_universe_aic_memory";
        String vectorName = "vector";
        int dimension = 0;
    }

    public static class KnowledgeStore {
        String collection = "moyu_universe_aic_ks";
        String vectorName = "vector";
        String documentsTable = "ks_documents";
        String embeddingProvider = null;
        String embeddingModel = null;
        int dimension = 0;
        String llmProvider = null;
        String llmModel = null;
        int queryCount = 8;
        int candidateLimit = 30;
        int chunkTokenThreshold = 2000;
        int embeddingBatchSize = 50;
        int defaultTtlDays = 30;
    }

    public static class Librarian {
        String provider = null;
        String model = null;
        List<String> tools;
        String systemPrompt = "你是一个图书管理员，负责查找和整合知识。";
        boolean thinking = false;
        boolean enableAsyncUpdate = true;
    }
}
