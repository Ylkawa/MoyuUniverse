package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.Providers.LLMProvider.Assistant;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.Universe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class Librarian {
    private final KnowledgeStore knowledgeStore;
    private final LLMProvider llmProvider;
    private final String model;
    private final List<String> toolNames;
    private final String systemPrompt;
    private final boolean thinking;
    private final boolean enableAsyncUpdate;
    private final Logger logger = LoggerFactory.getLogger(Librarian.class);

    public Librarian(KnowledgeStore knowledgeStore, Config.Librarian libCfg) {
        this.knowledgeStore = knowledgeStore;
        try {
            this.llmProvider = (LLMProvider) Universe.Providers.get(libCfg.provider);
        } catch (ClassCastException e) {
            throw new RuntimeException("Librarian LLM provider not found: " + libCfg.provider);
        }
        this.model = libCfg.model;
        this.toolNames = libCfg.tools != null ? libCfg.tools : List.of();
        this.systemPrompt = libCfg.systemPrompt;
        this.thinking = libCfg.thinking;
        this.enableAsyncUpdate = libCfg.enableAsyncUpdate;
    }

    public String search(String question) {
        long start = System.currentTimeMillis();
        logger.info("Librarian search: {}", question);

        CompletableFuture<String> localFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return localSearch(question);
            } catch (Exception e) {
                logger.error("Local search failed", e);
                return "（本地知识库查询失败）";
            }
        });

        CompletableFuture<String> webFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return webSearch(question);
            } catch (Exception e) {
                logger.error("Web search failed", e);
                return "（联网搜索失败）";
            }
        });

        String localResult;
        String webResult;
        try {
            localResult = localFuture.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Local search timeout", e);
            localResult = "（本地知识库查询超时）";
        }
        try {
            webResult = webFuture.get(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Web search timeout", e);
            webResult = "（联网搜索超时）";
        }

        logger.debug("Local search latency: {}ms", System.currentTimeMillis() - start);

        String merged;
        try {
            merged = mergeResults(question, localResult, webResult);
        } catch (Exception e) {
            logger.error("Merge failed, returning web result directly", e);
            merged = webResult;
        }

        if (enableAsyncUpdate) {
            MergeResult mr = parseSources(merged);
            merged = mr.cleanContent;
            if (!mr.sources.isEmpty()) {
                CompletableFuture.runAsync(() -> asyncUpdateKnowledge(mr.sources));
            }
        }

        logger.info("Librarian search complete: elapsed={}ms", System.currentTimeMillis() - start);
        return merged;
    }

    private String localSearch(String question) throws IOException {
        List<KnowledgeStore.KnowledgeResult> results = knowledgeStore.search(question);
        if (results.isEmpty()) return "（本地知识库无相关内容）";

        StringBuilder sb = new StringBuilder("本地知识库检索结果：\n");
        for (KnowledgeStore.KnowledgeResult r : results) {
            sb.append("[来源: ").append(r.url).append("]\n");
            sb.append("标题: ").append(r.title).append("\n");
            sb.append("问题: ").append(r.bestQuery).append("\n");
            sb.append("结论: ").append(r.bestConclusion).append("\n");
            sb.append("置信度: ").append(r.bestConfidence).append("\n");
            sb.append("相关度: ").append(r.bestScore).append("\n");
            sb.append("完整内容: ").append(r.content).append("\n\n");
        }
        return sb.toString();
    }

    private String webSearch(String question) throws IOException {
        Assistant assistant = new Assistant(llmProvider, model);
        assistant.setThinking(thinking);
        for (String toolName : toolNames) {
            for (LLMFunction func : AIChat.llmFunctions.get(toolName)) {
                assistant.addTool(func);
            }
        }

        MessageList ml = new MessageList();
        ml.add(MCMessage.Builder()
                .add(new TextField("""
                        你是一个研究助手。请尽力研究以下问题并给出详细准确的回答。
                        
                        在回答的最后，你必须输出你参考的所有信息来源，格式如下（每个来源之间空一行）：
                        
                        ===SOURCES===
                        URL: 来源网址
                        TITLE: 页面标题
                        CONTENT: 该来源的核心内容摘要
                        
                        URL: 来源网址2
                        TITLE: 页面标题2
                        CONTENT: 该来源的核心内容摘要2
                        ===END===
                        
                        如果没有可引用的来源（例如纯靠自身知识回答），仍然输出 ===SOURCES=== 和 ===END===，中间不写任何来源。
                        """))
                .build());
        ml.add(MCMessage.Builder()
                .add(new TextField(question))
                .build());

        CompletionsResponse resp = assistant.completions(ml, null);
        return resp.choices[0].message.content;
    }

    private String mergeResults(String question, String localResult, String webResult) throws IOException {
        Assistant assistant = new Assistant(llmProvider, model);
        assistant.setThinking(thinking);

        MessageList ml = new MessageList();
        ml.add(MCMessage.Builder()
                .add(new TextField("""
                        你是一个知识整合助手。你需要根据以下两方面的信息来回答问题：
                        1. 本地知识库的检索结果（可能过时或不完整）
                        2. 联网搜索的结果（通常更准确和最新）
                        
                        合并原则：
                        - 联网结果优先，本地结果作为补充
                        - 如果两者冲突，以联网结果为准
                        - 如果联网结果没有覆盖但本地有相关信息，可以采纳本地结果
                        - 如果两者都没有相关信息，如实说明
                        - 综合所有信息给出准确、完整的回答
                        
                        在回答的最后，你必须列出所有被采纳信息的来源URL，格式如下：
                        
                        ===SOURCES===
                        URL: 来源网址
                        TITLE: 页面标题
                        CONTENT: 该来源的核心内容摘要
                        
                        ===END===
                        
                        如果没有可列出的来源，仍然输出 ===SOURCES=== 和 ===END===，中间不写任何来源。
                        """))
                .build());
        ml.add(MCMessage.Builder()
                .add(new TextField("问题：" + question))
                .build());
        ml.add(MCMessage.Builder()
                .add(new TextField("本地知识库结果：\n" + localResult))
                .build());
        ml.add(MCMessage.Builder()
                .add(new TextField("联网搜索结果：\n" + webResult))
                .build());

        CompletionsResponse resp = assistant.completions(ml, null);
        return resp.choices[0].message.content;
    }

    private void asyncUpdateKnowledge(List<WebSource> sources) {
        for (WebSource source : sources) {
            if (source.url == null || source.url.isEmpty()) continue;
            try {
                if (!knowledgeStore.hasDocument(source.url)) {
                    logger.info("New source, indexing: url={}", source.url);
                    knowledgeStore.indexDocument(source.url, source.title, source.content);
                } else {
                    Long docId = knowledgeStore.getDocumentIdByUrl(source.url);
                    if (docId != null) {
                        String existing = knowledgeStore.getDocumentContent(docId);
                        if (existing != null && isConflicting(existing, source.content)) {
                            logger.info("Conflict detected, re-indexing: url={}", source.url);
                            knowledgeStore.indexDocument(source.url, source.title, source.content);
                        } else {
                            logger.debug("No conflict, skip: url={}", source.url);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Async update failed for url={}", source.url, e);
            }
        }
    }

    private boolean isConflicting(String existing, String newContent) {
        try {
            Assistant assistant = new Assistant(llmProvider, model);
            MessageList ml = new MessageList();
            ml.add(MCMessage.Builder()
                    .add(new TextField("""
                            判断以下两段内容是否存在事实性冲突（关键数据、结论、时间、参数等发生了改变）。
                            轻微措辞变化或补充信息不算冲突。
                            只回答 YES 或 NO。
                            """))
                    .build());
            ml.add(MCMessage.Builder()
                    .add(new TextField("已有内容：\n" + truncate(existing, 2000)))
                    .build());
            ml.add(MCMessage.Builder()
                    .add(new TextField("新内容：\n" + truncate(newContent, 2000)))
                    .build());

            CompletionsResponse resp = assistant.completions(ml, null);
            String answer = resp.choices[0].message.content.trim().toUpperCase();
            boolean conflict = answer.contains("YES");
            logger.debug("Conflict check: {}", conflict);
            return conflict;
        } catch (Exception e) {
            logger.warn("Conflict detection failed, defaulting to re-index", e);
            return true;
        }
    }

    private MergeResult parseSources(String content) {
        MergeResult result = new MergeResult();
        int sourcesIdx = content.indexOf("===SOURCES===");
        if (sourcesIdx < 0) {
            result.cleanContent = content;
            return result;
        }

        result.cleanContent = content.substring(0, sourcesIdx).trim();
        String sourcesBlock = content.substring(sourcesIdx + "===SOURCES===".length());
        int endIdx = sourcesBlock.indexOf("===END===");
        if (endIdx >= 0) sourcesBlock = sourcesBlock.substring(0, endIdx);

        String[] blocks = sourcesBlock.split("\n\\s*\n");
        for (String block : blocks) {
            WebSource source = new WebSource();
            for (String line : block.split("\n")) {
                if (line.startsWith("URL:")) source.url = line.substring(4).trim();
                else if (line.startsWith("TITLE:")) source.title = line.substring(6).trim();
                else if (line.startsWith("CONTENT:")) source.content = line.substring(8).trim();
            }
            if (source.url != null && !source.url.isEmpty()) result.sources.add(source);
        }
        return result;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private static class WebSource {
        String url;
        String title;
        String content;
    }

    private static class MergeResult {
        String cleanContent;
        List<WebSource> sources = new ArrayList<>();
    }
}
