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
    Mail Mail = new Mail();
    SkillConfig Skills = null;
    com.nekoyu.Universe.AIChat.graph.GraphSettings Graph = new com.nekoyu.Universe.AIChat.graph.GraphSettings();

    public static class SkillConfig {
        String[] defaultSkills;
        String[] availableSkills;
    }

    public static class SQLConfig {
        String url;
        String user;
        String password;
    }

    public static class Qdrant {
        public String address = "127.0.0.1";
        public int port = 6334;
        public boolean encryptedConnection = true;
        public String secretKey = null;
        public String provider = null;
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
        public String collection = "moyu_universe_aic_ks";
        public String vectorName = "vector";
        public String documentsTable = "aichat.knowledge_document";
        public String embeddingProvider = null;
        public String embeddingModel = null;
        public int dimension = 0;
        public String llmProvider = null;
        public String llmModel = null;
        public int queryCount = 8;
        public int candidateLimit = 30;
        public int chunkTokenThreshold = 2000;
        public int embeddingBatchSize = 50;
        public int defaultTtlDays = 30;
    }

    public static class Librarian {
        public String provider = null;
        public String model = null;
        public List<String> tools;
        public boolean thinking = false;
        public boolean enableAsyncUpdate = true;
    }

    public static class Mail {
        boolean enabled = false;

        String protocol = "imaps";
        String host;
        int port = 993;
        String username;
        String password;
        String folder = "INBOX";

        String timezone = "Asia/Shanghai";
        List<String> dailyTimes = List.of("08:00");

        // 首次收取最近多少天的邮件；0 表示全部历史，后续按处理进度增量收取。
        int initialLookbackDays = 0;
    }
}
