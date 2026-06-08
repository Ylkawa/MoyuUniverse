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
    ExternalKnowledgeBase ExternalKnowledgeBase = null;

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

    public static class ExternalKnowledgeBase {
        String collection = "moyu_universe_ekb_memory";
        String vectorName = "vector";
        String provider = null;
        String embeddingProvider = null;
        String model = null;
        String embeddingModel = null;
        int dimension = 0;
        boolean enable_thinking = false;
        WebCatch webCatchAgent = new WebCatch();

        public static class WebCatch {
            Fetcher fetcher = new Fetcher();
            Parser parser = new Parser();

            public static class Fetcher {
                String provider = null;
                String model = null;
                List<String> tools;
                String promptFirst = "";
                String promptLast = "";
            }

            public static class Parser {
                String provider = null;
                String model = null;
                String promptFirst = "你是一个负责维护知识库的助手，请你按照用户指引完成知识库的整理工作";
                String promptLast = "";
            }
        }
    }
}
