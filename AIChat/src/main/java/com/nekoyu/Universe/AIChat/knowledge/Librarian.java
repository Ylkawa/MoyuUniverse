package com.nekoyu.Universe.AIChat.knowledge;

import com.nekoyu.Universe.AIChat.AIChat;
import com.nekoyu.Universe.AIChat.ChatAssistant;
import com.nekoyu.Universe.AIChat.ChatContext;
import com.nekoyu.Universe.AIChat.Config;

import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.Universe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 研究只输出自然语言；结构化提取在研究完成后的知识库编排中进行。 */
public class Librarian {
    private static final Logger logger = LoggerFactory.getLogger(Librarian.class);
    static final String RESEARCH_PROMPT = """
            你是一个研究与资料整理助手。研究问题，或核查、补充并整理输入资料。输入资料是线索，不自动视为事实。
            使用自然语言，不输出 JSON，不使用固定字段或特殊标记包装报告。

            研究过程：
            - 每轮工具调用前，用简明自然语言说明已经发现什么、哪些问题尚待查证、接下来准备查什么。只给出面向协作的进度摘要，不输出内部思维链。
            - 研究一个问题时围绕目标查找证据；整理资料时识别主体、来源、发布时间、主要事实与待核查处，筛选内容并归并重复报道。
            - 不止复述输入。根据明确目标和场景，按相关性研究变化、替代方案、实际影响、使用入口和条件、价格额度、版本与地区限制、效果证据及不足；不作无关延伸，不强行填满栏目。
            - 未提供用户偏好时，依据目标读者普遍关心的实际影响，不编造个人兴趣、身份或立场。
            - 优先核查原始公告、官方文档、论文和其他直接证据。区分宣传、演示、基准与作者体验，缺少可比条件时不作确定的优劣判断。
            - 本地知识仅作为可能过时或不完整的材料，核对主体、版本、平台、地区与时间；当前状态需要时间适用的证据。范围不同的说法分别说明，不把历史事实当成当前事实。
            - 证据充分或工具无法提供更多信息时结束；核查失败或工具不可用时说明限制，不用自身知识冒充研究结果。

            内容筛选：
            - 排除政治立场、党派人物、政治动员、地缘对抗和意识形态争论；技术或产品相关的合规、监管、可用地区信息仅保留有来源的必要事实与实际影响。
            - 排除明星私生活、娱乐八卦、饭圈应援、拉踩、争吵与粉丝排行；影视、音乐、游戏和创作工具的技术产品信息可保留。
            - 排除无实质信息的广告、带货、引流、标题党和情绪煽动；商业公告保留有实质变化的事实，剥离宣传和未经证实的承诺。
            - 不传播无关隐私、私人联系方式和凭据，不复现色情、仇恨、暴力煽动或用于诈骗、攻击、违法伤害的操作细节；安全事件只保留必要事实、影响与防护信息。
            - 健康、法律、金融等重大决策核对适用条件并保留来源归属，不把宣传或个别案例改成诊断、法律定论、收益保证或确定行动建议。
            - 混合资料只保留能独立理解的合格部分。全部不合格时说明没有适合整理的内容，不扩写被剔除部分。

            最终退出报告：
            - 最后一段回答必须重新完整输出一份可独立阅读的研究报告，即使前面的进度已经提过，也要完整重述全部必要结论、依据、实际来源链接、适用条件、分歧和未确认事项。
            - 禁止以“前面已经说过”“同上”代替正文，禁止只列新增发现。达到工具预算时仍按此要求收尾，并说明研究限制。
            - 开头说明主题和适用范围，多主题分别保留主体、日期和条件。按需要保留时间线，不把转载数量当作独立佐证。
            - 区分证据支持的事实、来源方说法、作者观点和待确认信息。未知日期不擅自把“今天”“最新”改成确定日期；价格、额度、排名、可用性保留核查时间和条件。
            - 网址仅来自输入明确提供或本轮工具实际返回的链接，不凭记忆生成。每个引用只支持对应来源的内容，不把跨来源推断归给单一来源。
            - 输入摘要未读原文时注明“输入提供的摘要，未核对原文”；只有搜索摘要时注明证据范围，不声称阅读全文。阅读聚合页不等于读过原始报道。
            - 保留“来源方声称”“仅有摘要”“未核对原文”“尚未确认”等限制，没有可引用来源时明确说明，不补造事实或网址。
            """;

    @FunctionalInterface interface LocalSearch { String search(String question) throws Exception; }
    record CompletedResearch(String question, String report, Map<String, String> evidence) {}
    private final LLMProvider llmProvider;
    private final Config.Librarian config;
    private final LocalSearch localSearch;
    private final Consumer<CompletedResearch> ingest;

    public Librarian(KnowledgeStore store, Config.Librarian config) {
        this((LLMProvider) Universe.Providers.get(config.provider), config,
                question -> store == null ? "（本地知识库未启用）" : localSearch(store, question),
                result -> { if (store != null) store.ingestResearch(result.question(), result.report(), result.evidence()); });
    }

    Librarian(LLMProvider provider, Config.Librarian config, LocalSearch localSearch, Consumer<CompletedResearch> ingest) {
        this.llmProvider = Objects.requireNonNull(provider, "Librarian LLM provider not found: " + config.provider);
        this.config = config;
        this.localSearch = localSearch;
        this.ingest = ingest;
    }

    /** 非会话调用者仍可同步取得最终完整报告。 */
    public String search(String question) {
        AtomicReference<String> last = new AtomicReference<>("");
        search(question, message -> last.set(message.toString()));
        return last.get();
    }

    /** 由异步工具执行线程调用；每段完整反馈立即通过 Calling 返回。 */
    public void search(String question, Calling calling) {
        search(question, calling, TimeUnit.MINUTES.toMillis(10));
    }

    void search(String question, Calling calling, long timeoutMillis) {
        long start = System.nanoTime();
        Feedback feedback = new Feedback(calling);
        CompletableFuture<String> local = CompletableFuture.supplyAsync(() -> {
            try { return localSearch.search(question); }
            catch (Exception error) {
                logger.warn("Librarian local search failed", error);
                return "（本地知识库查询失败，继续联网研究）";
            }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Librarian-Research");
            thread.setDaemon(true);
            return thread;
        });
        Future<CompletedResearch> research = worker.submit(() -> research(question, local, feedback));
        try {
            CompletedResearch result = research.get(timeoutMillis, TimeUnit.MILLISECONDS);
            feedback.finish(result.report());
            if (config.enableAsyncUpdate) CompletableFuture.runAsync(() -> {
                try { ingest.accept(result); }
                catch (Exception error) { logger.error("Librarian knowledge ingestion failed", error); }
            });
        } catch (TimeoutException error) {
            feedback.finish("研究超时：未能生成完整最终报告。此前阶段反馈仍可参考，但不能视为完整研究结论。");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            feedback.finish("研究已中断，未生成完整最终报告。");
        } catch (ExecutionException error) {
            logger.error("Librarian research failed", error.getCause());
            feedback.finish("研究失败：未能生成完整最终报告。" + Objects.toString(error.getCause().getMessage(), ""));
        } finally {
            research.cancel(true);
            local.cancel(true);
            worker.shutdownNow();
            logger.info("Librarian search complete: model={}, elapsed={}ms", config.model,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    private CompletedResearch research(String question, CompletableFuture<String> local, Feedback feedback) throws IOException {
        ChatContext context = new ChatContext();
        context.setSystemPromptFirst(RESEARCH_PROMPT);
        context.userMsg(new Message(question));
        ResearchEvidence evidence = new ResearchEvidence(question);
        AtomicReference<String> finalReport = new AtomicReference<>();
        boolean[] localAdded = {false};
        LLMProvider observed = new LLMProvider() {
            @Override public CompletionsResponse completions(String model, List<Message> messages, List<LLMFunction> tools,
                                                            CompletionsRequest request, BufferCallback callback) throws IOException {
                if (Thread.currentThread().isInterrupted() || feedback.finished()) throw new IOException("研究已结束");
                evidence.observeTools(messages);
                if (!localAdded[0] && local.isDone() && !local.isCancelled()) {
                    Message material = new Message("本地知识库材料（可能过时，仅作线索）：\n" + local.join());
                    material.role = "user";
                    context.userMsg(material);
                    messages.add(material);
                    evidence.observe(material.toString());
                    localAdded[0] = true;
                }
                CompletionsResponse response = llmProvider.completions(model, messages, tools, request, null);
                if (Thread.currentThread().isInterrupted() || feedback.finished()) throw new IOException("研究已结束");
                if (response == null || response.choices == null || response.choices.length == 0)
                    throw new IOException("模型没有返回有效研究结果");
                var choice = response.choices[0];
                String text = choice.message.content;
                if ("tool_calls".equals(choice.finish_reason)) {
                    if (!"none".equals(request.toolChoice)) {
                        if (text != null && !text.isBlank()) feedback.progress(text);
                        else if (choice.message.tool_calls != null) {
                            for (Tool_call call : choice.message.tool_calls) if (call.function != null)
                                feedback.progress("接下来调用 " + call.function.name + "，查询目标：" + call.function.arguments);
                        }
                    }
                } else if ("stop".equals(choice.finish_reason) && text != null && !text.isBlank()) {
                    finalReport.set(text);
                } else throw new IOException("研究输出未完整结束：" + choice.finish_reason);
                return response;
            }
        };
        ChatAssistant assistant = new ChatAssistant(observed, config.model);
        CompletionsRequest request = new CompletionsRequest();
        request.enable_thinking = config.thinking;
        assistant.setCompletionsRequest(request);
        assistant.setChatContext(context);
        if (config.tools != null) for (String tool : config.tools) AIChat.addRegisteredTools(assistant, tool);
        assistant.completions(null);
        String report = finalReport.get();
        if (report == null) throw new IOException("研究未生成完整最终报告");
        return new CompletedResearch(question, report, evidence.forReport(report));
    }

    private static String localSearch(KnowledgeStore store, String question) throws IOException {
        var results = store.search(question);
        if (results.isEmpty()) return "（本地知识库无相关内容）";
        StringBuilder text = new StringBuilder();
        for (var result : results) text.append("[来源: ").append(result.url).append("]\n标题: ").append(result.title)
                .append("\n问题: ").append(result.bestQuery).append("\n结论: ").append(result.bestConclusion)
                .append("\n置信度: ").append(result.bestConfidence).append("\n相关度: ").append(result.bestScore)
                .append("\n完整内容: ").append(result.content).append("\n\n");
        return text.toString();
    }

    /** 同步封口，超时之后的迟到模型结果不能继续反馈或触发入库。 */
    private static final class Feedback {
        private final Calling calling;
        private boolean finished;
        Feedback(Calling calling) { this.calling = calling; }
        synchronized boolean finished() { return finished; }
        synchronized void progress(String text) { if (!finished) paragraphs(text).forEach(t -> calling.calling(new Message(t))); }
        synchronized void finish(String text) {
            if (finished) return;
            finished = true;
            // 最终报告作为一条完整消息返回，不能按段落拆散。
            calling.calling(new Message(text));
        }
        private static List<String> paragraphs(String text) {
            return Arrays.stream(text.split("\\R\\s*\\R")).filter(s -> !s.isBlank()).toList();
        }
    }
}
