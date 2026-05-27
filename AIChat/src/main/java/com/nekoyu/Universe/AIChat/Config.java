package com.nekoyu.Universe.AIChat;

public class Config {
    String PromptFirst;
    String PromptLast;
    String ProviderId;
    String MemoryModel;

    SQLConfig SQLConfig;
    QdrantConfig QdrantConfig;

    public static class SQLConfig {
        String url;
        String user;
        String password;
    }

    public static class QdrantConfig {
        String address = "127.0.0.1";
        int port = 6334;
        boolean encryptedConnection = true;
        String secretKey = null;
        String collection = "moyu_universe_aic_meomory";
        String provider = null;
    }
}
