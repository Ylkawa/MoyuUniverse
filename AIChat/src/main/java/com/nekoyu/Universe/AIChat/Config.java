package com.nekoyu.Universe.AIChat;

public class Config {
    String PromptFirst;
    String PromptLast;
    String ProviderId;
    String MemoryModel;

    SQLConfig SQLConfig;

    public static class SQLConfig {
        String url;
        String user;
        String password;
    }
}
