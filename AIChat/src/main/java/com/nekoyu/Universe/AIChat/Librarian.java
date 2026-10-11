package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest;
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

        CompletableFuture<LibrarianResult> webFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return webSearch(question);
            } catch (Exception e) {
                logger.error("Web search failed", e);
                return LibrarianResult.withoutSources("（联网搜索失败）");
            }
        });

        String localResult;
        LibrarianResult webResult;
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
            webResult = LibrarianResult.withoutSources("（联网搜索超时）");
        }

        logger.debug("Local search latency: {}ms", System.currentTimeMillis() - start);

        LibrarianResult merged;
        try {
            merged = mergeResults(question, localResult, webResult);
        } catch (Exception e) {
            logger.error("Merge failed, returning web result directly", e);
            merged = webResult;
        }

        if (enableAsyncUpdate) {
            if (!merged.sources().isEmpty()) {
                List<LibrarianResult.Source> sources = merged.sources();
                CompletableFuture.runAsync(() -> asyncUpdateKnowledge(sources));
            }
        }

        logger.info("Librarian search complete: elapsed={}ms", System.currentTimeMillis() - start);
        return merged.answer();
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

    private LibrarianResult webSearch(String question) throws IOException {
        ChatAssistant assistant = new ChatAssistant(llmProvider, model);
        CompletionsRequest request = new CompletionsRequest();
        request.enable_thinking = thinking;
        request.responseFormat = StructuredOutputs.research();
        assistant.setCompletionsRequest(request);
        for (String toolName : toolNames) {
            AIChat.addRegisteredTools(assistant, toolName);
        }

        ChatContext chatContext = new ChatContext();
        chatContext.userMsg(new Message("""
                你是一个研究助手。请尽力研究以下问题并给出详细准确的回答。
                回答必须在开头明确指出该信息所属的【主体】（如具体游戏、产品、系统的名称）与【适用范围】（如版本号、平台、模式、时间段）。
                如果问题本身未指明主体，先判断最可能的游戏/产品并在回答首行明确写明，禁止用"新版本""该游戏""这个系统"等含糊表述带过。
                
                按提供的输出 Schema 返回完整回答和实际参考的信息来源。
                来源摘要可包含多行；没有可引用来源时 sources 为空数组。不要编造来源网址。
                """));
        chatContext.userMsg(new Message(question));

        assistant.setChatContext(chatContext);
        CompletionsResponse resp = assistant.completions(null);
        return LibrarianResult.parse(StructuredOutputs.content(resp));
    }

    private LibrarianResult mergeResults(String question, String localResult, LibrarianResult webResult) throws IOException {
        ChatAssistant assistant = new ChatAssistant(llmProvider, model);
        CompletionsRequest request = new CompletionsRequest();
        request.enable_thinking = thinking;
        request.responseFormat = StructuredOutputs.research();
        assistant.setCompletionsRequest(request);

        ChatContext chatContext = new ChatContext();
        chatContext.userMsg(new Message("""
                你是一个知识整合助手。你需要根据以下两方面的信息来回答问题：
                1. 本地知识库的检索结果（可能过时或不完整）
                2. 联网搜索的结果（通常更准确和最新）
                
                合并原则：
                - 联网结果优先，本地结果作为补充
                - 如果两者冲突，以联网结果为准
                - 如果联网结果没有覆盖但本地有相关信息，可以采纳本地结果
                - 如果两者都没有相关信息，如实说明
                - 综合所有信息给出准确、完整的回答
                - 回答开头必须明确指出信息所属的【主体】（具体游戏/产品/系统名）与【适用范围】（版本号/平台/模式）
                - 如果问题本身或检索结果无法确定主体与适用范围，如实说明，严禁编造主体或用"新版本/该游戏/这个系统"等含糊表述充数
                
                按提供的输出 Schema 返回完整回答和所有被采纳信息的来源。
                来源摘要可包含多行；没有可引用来源时 sources 为空数组。不要编造来源网址。
                """));
        chatContext.userMsg(new Message("问题：" + question));
        chatContext.userMsg(new Message("本地知识库结果：\n" + localResult));
        chatContext.userMsg(new Message("联网搜索结果：\n" + webResult.toJson()));

        assistant.setChatContext(chatContext);
        CompletionsResponse resp = assistant.completions(null);
        return LibrarianResult.parse(StructuredOutputs.content(resp));
    }

    private void asyncUpdateKnowledge(List<LibrarianResult.Source> sources) {
        for (LibrarianResult.Source source : sources) {
            if (source.url() == null || source.url().isEmpty()) continue;
            try {
                if (!knowledgeStore.hasDocument(source.url())) {
                    logger.info("New source, indexing: url={}", source.url());
                    knowledgeStore.indexDocument(source.url(), source.title(), source.content());
                } else {
                    Long docId = knowledgeStore.getDocumentIdByUrl(source.url());
                    if (docId != null) {
                        String existing = knowledgeStore.getDocumentContent(docId);
                        if (existing != null && isConflicting(existing, source.content())) {
                            logger.info("Conflict detected, re-indexing: url={}", source.url());
                            knowledgeStore.indexDocument(source.url(), source.title(), source.content());
                        } else {
                            logger.debug("No conflict, skip: url={}", source.url());
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Async update failed for url={}", source.url(), e);
            }
        }
    }

    private boolean isConflicting(String existing, String newContent) {
        try {
            ChatAssistant assistant = new ChatAssistant(llmProvider, model);
            CompletionsRequest request = new CompletionsRequest();
            request.responseFormat = StructuredOutputs.conflict();
            assistant.setCompletionsRequest(request);
            ChatContext chatContext = new ChatContext();
            chatContext.userMsg(new Message("""
                    判断以下两段内容是否存在事实性冲突（关键数据、结论、时间、参数等发生了改变）。
                    轻微措辞变化或补充信息不算冲突。
                    按提供的输出 Schema 返回判断结果，conflict 表示是否存在事实性冲突。
                    """));
            chatContext.userMsg(new Message("已有内容：\n" + truncate(existing, 2000)));
            chatContext.userMsg(new Message("新内容：\n" + truncate(newContent, 2000)));

            assistant.setChatContext(chatContext);
            CompletionsResponse resp = assistant.completions(null);
            boolean conflict = LibrarianResult.parseConflict(StructuredOutputs.content(resp));
            logger.debug("Conflict check: {}", conflict);
            return conflict;
        } catch (Exception e) {
            logger.warn("Conflict detection failed, defaulting to re-index", e);
            return true;
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

}
