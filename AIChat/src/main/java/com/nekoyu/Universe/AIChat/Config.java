package com.nekoyu.Universe.AIChat;

public class Config {
    String PromptFirst = "";
    String PromptLast = "";
    String ProviderId = null;
    String MemoryModel = null;

    Qdrant Qdrant = null;
    Annotator Annotator = null;

    public static class Qdrant {
        String address = "127.0.0.1";
        int port = 6334;
        boolean encryptedConnection = true;
        String secretKey = null;
        String collection = "moyu_universe_aic_memory";
        String provider = null;
    }

    public static class Annotator {
        String provider = null;
        String embeddingProvider = null;
        String model = null;
    }
}
