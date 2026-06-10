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
        String collection = "moyu_universe_aic_ekb";
        String vectorName = "vector";
        String provider = null;
        String embeddingProvider = null;
        String model = null;
        String embeddingModel = null;
        int dimension = 0;
        boolean enable_thinking = false;
        WebCatch webCatch = new WebCatch();

        public static class WebCatch {
            Fetcher fetcher = new Fetcher();
            Parser parser = new Parser();

            public static class Fetcher {
                String provider = null;
                String model = null;
                List<String> tools;
                String promptFirst = """
                        你是一个负责从互联网获取信息并整理结果的助手。
                        
                        你的任务是围绕用户问题尽力收集与之直接相关的、可验证的信息，并整理成“事实卡片”。
                        
                        要求：
                        1. 只输出与用户问题直接相关的内容；强相关的补充知识可以一并输出，但不要扩展到无关百科内容。
                        2. 每条信息必须是独立事实，尽量原子化、一条一句。
                        3. 不要输出搜索过程、打开链接过程、推理过程、闲聊语句。
                        4. 每条事实都必须包含：
                           - 事实内容
                           - 来源网址
                           - 可信度
                           - 预计有效期
                        5. 尽量用用户会搜索的说法来写事实内容，避免过度书面化。
                        6. 如果不同来源存在冲突，要明确标注冲突，不要强行合并。
                        7. 优先输出结论，其次再输出必要的补充事实。
                        
                        输出格式严格为：
                        
                        - 事实：...
                          来源：...
                          可信度：...
                          有效期：...
                        
                        每条事实之间空一行。""";
                String promptLast = "";
                boolean enable_thinking = false;
            }

            public static class Parser {
                String provider = null;
                String model = null;
                String promptFirst = "你是一个负责维护知识库的助手，请你按照用户指引完成知识库的整理工作";
                String promptLast = "";
                boolean enable_thinking = false;
            }
        }
    }
}
