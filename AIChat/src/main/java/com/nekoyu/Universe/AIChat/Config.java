package com.nekoyu.Universe.AIChat;

public class Config {
    String Prompt;

    SQLConfig SQLConfig;

    public static class SQLConfig {
        String url;
        String user;
        String password;
    }
}
