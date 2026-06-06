package com.nekoyu.Universe.AIChat;

public class Config {
    String PromptFirst = "";
    String PromptLast = "";
    String ProviderId = null;

    Qdrant Qdrant = null;
    Annotator Annotator = null;
    Memory Memory = null;
    ExternalKnowledgeBase ExternalKnowledgeBase = null;

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

    public static class ExternalKnowledgeBase {
        String collection = "moyu_universe_ekb_memory";
        String vectorName = "vector";
        String provider = null;
        String embeddingProvider = null;
        String model = null;
        int dimension = 0;
        boolean enable_thinking = false;
    }
}
