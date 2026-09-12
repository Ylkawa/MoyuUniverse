package com.nekoyu.Universe.AIChat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.nekoyu.Universe.API.MessageChannel.Account;
import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Context;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.ContentPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.ImageUrlPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.TextPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Tool_call;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 对话状态机：保存会话的 canonical conversation state，并负责
 * assistant / tool / reasoning 消息的写入、Function Calling 的 Tool Loop、
 * 以及"何时再次请求模型"的决策。
 * <p>
 * LLMProvider 在这里只扮演"传输协议适配器"：每次 {@link #runTurn} 内部调用一次 provider，
 * 拿回一次 Model Response，由本状态机决定 stop / tool_calls / 其他 finish_reason。
 */
public class ChatContext implements Context {
    private static final String ROLE = "role";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_TOOL = "tool";
    private static final String ROLE_SYSTEM = "system";

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatContext.class);
    private static final Gson GSON = new Gson();

    /** 底层会话消息（MCMessage），兼容旧实现：assistant/tool 消息直接写回此列表 */
    private final MessageList base;
    /** 用于区分 assistant 消息的角色判定账号 */
    private final Account assistantAccount;
    private String systemPromptFirst = null;
    private String systemPromptLast = null;

    /** 新到达待批次处理的消息（用户触发消息与异步工具结果），静默去抖后统一触发回复 */
    private final Deque<MCMessage> pendingMsgs = new ArrayDeque<>();
    private volatile long lastNewMessageTime = 0;
    private volatile long debounceMillis = 5_000;
    private volatile ScheduledExecutorService debounceScheduler = null;
    private volatile ScheduledFuture<?> debounceTask = null;
    /** 去抖到期回调（由上层注入，负责检查条件并触发回复） */
    private volatile Runnable onDebounceFire = null;

    public ChatContext() {
        this(new MessageList(), null);
    }

    public ChatContext(MessageList ml) {
        this(ml, null);
    }

    public ChatContext(MessageList ml, Account assistantAccount) {
        this.base = ml == null ? new MessageList() : ml;
        this.assistantAccount = assistantAccount;
    }

    public static ChatContext from(MessageList ml) {
        return new ChatContext(ml, null);
    }

    public static ChatContext from(MessageList ml, Account assistantAccount) {
        return new ChatContext(ml, assistantAccount);
    }

    public void setSystemPromptFirst(String systemPromptFirst) {
        this.systemPromptFirst = systemPromptFirst;
    }

    public void setSystemPromptLast(String systemPromptLast) {
        this.systemPromptLast = systemPromptLast;
    }

    public MessageList getBase() {
        return base;
    }

    /**
     * 配置去抖参数与到期回调（开启异步工具结果时调用）。
     *
     * @param debounceMillis   静默等待时长（毫秒）
     * @param scheduler        用于排期去抖任务的线程池
     * @param onDebounceFire   到期回调
     */
    public void configureDebounce(long debounceMillis, ScheduledExecutorService scheduler, Runnable onDebounceFire) {
        this.debounceMillis = debounceMillis;
        this.debounceScheduler = scheduler;
        this.onDebounceFire = onDebounceFire;
    }

    /** 是否有待批次处理的新消息 */
    public synchronized boolean hasPendingMessages() {
        return !pendingMsgs.isEmpty();
    }

    /** 待批次处理的消息句柄（快照，不移除） */
    public synchronized List<MCMessage> peekPendingMessages() {
        return new ArrayList<>(pendingMsgs);
    }

    /** 取出并清空待批次处理的消息 */
    public synchronized List<MCMessage> drainPendingMessages() {
        List<MCMessage> drained = new ArrayList<>(pendingMsgs);
        pendingMsgs.clear();
        return drained;
    }

    public long getLastNewMessageTime() {
        return lastNewMessageTime;
    }

    /**
     * 记录一条需要处理的新消息并重置静默去抖计时。
     * 新消息（用户触发消息 / 异步工具结果）到达时会前取消旧定时并重新排期。
     */
    public synchronized void enqueuePendingMessage(MCMessage msg) {
        pendingMsgs.add(msg);
        lastNewMessageTime = System.currentTimeMillis();
        rescheduleDebounce();
    }

    private synchronized void rescheduleDebounce() {
        if (debounceScheduler == null) return;
        if (debounceTask != null) debounceTask.cancel(false);
        debounceTask = debounceScheduler.schedule(() -> {
            Runnable fire = onDebounceFire;
            if (fire != null) fire.run();
        }, debounceMillis, TimeUnit.MILLISECONDS);
    }

    /** 以指定时长重新排期去抖（用于未决工具尚未回应时延长等待） */
    public synchronized void rearmDebounce(long delayMillis) {
        if (debounceScheduler == null) return;
        if (debounceTask != null) debounceTask.cancel(false);
        debounceTask = debounceScheduler.schedule(() -> {
            Runnable fire = onDebounceFire;
            if (fire != null) fire.run();
        }, Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
    }

    /** 会话关闭时取消未触发的去抖任务 */
    public synchronized void cancelDebounce() {
        if (debounceTask != null) debounceTask.cancel(false);
        debounceTask = null;
    }

    /**
     * 执行一整轮"模型调用 + 工具执行"循环（Agent / Function Calling 状态机）。
     * <p>
     * 每轮：依据当前 canonical state 生成请求消息 → 调用一次 {@link LLMProvider}（Provider 只负责协议序列化 /
     * API 调用 / SSE 解析，不驱动循环）→ 按 finish_reason 决策：
     * <ul>
     *     <li>stop：把最终 assistant 回复写入本 Context，结束本回合；</li>
     *     <li>tool_calls：把 assistant(tool_calls) 写入本 Context，按模型给定的有序参数执行工具，
     *         把每个 tool 结果写入本 Context，随后继续请求模型；</li>
     *     <li>其他未知 finish_reason：结束本回合。</li>
     * </ul>
     * 单次回复内的工具执行次数与总请求轮数受 Agent 的 {@link ToolLoopOptions} 约束；完全相同的工具参数
     * 不会重复执行（去重），防止模型陷入工具调用死循环。
     *
     * @return 本回合累计的 Model Response（内容/usage 为各轮合并结果，含工具轮次间的 "\n\n" 分隔）
     */
    public CompletionsResponse runTurn(LLMProvider provider, String model, List<LLMFunction> tools, CompletionsRequest request, ToolLoopOptions options, LLMProvider.BufferCallback bufferCallback) throws IOException {
        CompletionsResponse responding = newFakeResponse();
        options = options == null ? new ToolLoopOptions() : options;
        int maxCalls = Math.max(1, options.MaxToolCallsPerTurn);
        int maxRounds = Math.max(1, options.MaxToolRounds);
        int calls = 0;
        int rounds = maxRounds;
        Map<String, String> exactResults = new HashMap<>();
        Map<String, LLMFunction> functionsMap = new HashMap<>();

        while (true) {
            if (rounds <= 0) { // 兜底：无论如何都要终结循环
                LOGGER.error("{} 工具调用循环未能正常中止，强制返回当前输出", model);
                return responding;
            }
            // 每轮重建函数映射，保证轮次中途新增的工具（如动态激活技能后追加的）能立即被解析执行
            functionsMap.clear();
            if (tools != null) for (LLMFunction llmFunction : tools) {
                if (llmFunction != null) functionsMap.put(llmFunction.name, llmFunction);
            }
            // 回合超时或本轮工具调用已达上限时，进行最后一次请求，避免死循环。
            // 关键：不再把 tools 置空——tools 会被渲染进 prompt 前部，移除会使前缀（含已累积的整段上下文）缓存全部失效，
            // 而这恰恰发生在工具链最长、上下文最大、最该命中缓存的收官请求上。
            // 改为保留 tools 列表不变、仅通过 tool_choice="none" 禁止模型再次调用工具，在强制收尾的同时复用上下文缓存。
            boolean disableTools = (rounds <= 1) || (calls >= maxCalls);
            boolean hasTools = tools != null && !tools.isEmpty();
            List<Message> roundMessages = getMessageList();
            List<LLMFunction> roundTools = tools;
            if (disableTools) {
                if (calls >= maxCalls)
                    LOGGER.warn("{} 单次回复内工具调用次数已达上限({})，已禁止继续调用工具，强制模型文字回复", model, maxCalls);
                if (hasTools) {
                    request.toolChoice = "none"; // 保留 tools 前缀不变，仅禁止再次调用
                } else {
                    request.toolChoice = null;
                    roundTools = null;
                }
                Message am = new Message("[WARNING] 工具调用回合超时或工具调用已达次数上限，工具已被禁用，请立即停止调用工具，直接基于已有的信息作答");
                am.role = ROLE_SYSTEM;
                roundMessages.add(am);
            } else {
                request.toolChoice = null;
            }
            CompletionsResponse round = provider.completions(model, roundMessages, roundTools, request, bufferCallback);
            if (round == null) {
                LOGGER.error("{} Provider 返回 null，结束本回合", model);
                return responding;
            }
            // 合并 usage 与 finish_reason 到累计响应
            responding.usage.total_tokens += round.usage.total_tokens;
            responding.usage.prompt_tokens += round.usage.prompt_tokens;
            responding.usage.completion_tokens += round.usage.completion_tokens;
            String finish = round.choices[0].finish_reason;
            responding.choices[0].finish_reason = finish;
            String content = round.choices[0].message.content;
            String reasoning = round.choices[0].message.reasoning_content;
            if (content != null) responding.choices[0].message.content += content;
            if (reasoning != null) responding.choices[0].message.reasoning_content += reasoning;

            if ("stop".equals(finish)) {
                Message assistantMsg = new Message(content == null ? "" : content);
                if (reasoning != null) assistantMsg.reasoning_content = reasoning;
                assistantMsg(assistantMsg);
                return responding;
            }
            if ("tool_calls".equals(finish)) {
                // 收官轮已通过 tool_choice="none" 要求停止调用工具，但 Provider 未严格遵守仍返回 tool_calls：
                // 直接结束本回合，且绝不写入 assistant(tool_calls) —— 否则会留下一条没有对应 tool 结果的悬空消息，
                // 破坏消息序列合法性，导致后续每一轮请求都被 API 拒绝。
                if (disableTools) {
                    LOGGER.warn("{} 收官轮已禁用工具，但模型仍返回 tool_calls，忽略并结束本回合", model);
                    break;
                }
                boolean outputted = content != null && !content.isBlank();
                if (outputted) {
                    responding.choices[0].message.content += "\n\n";
                    if (bufferCallback != null) bufferCallback.onCompletion("\n\n");
                }
                Message assistantMsg = new Message(content == null ? "" : content);
                if (reasoning != null) assistantMsg.reasoning_content = reasoning;
                Tool_call[] toolCalls = round.choices[0].message.tool_calls;
                assistantMsg.tool_calls = toolCalls;
                assistantMsg(assistantMsg);
                if (toolCalls == null || toolCalls.length == 0) break;
                boolean next = false;
                for (Tool_call toolCall : toolCalls) {
                    if (toolCall.function == null) {
                        LOGGER.warn("{} 模型返回的 tool_call 缺少 function 定义，忽略", model);
                        continue;
                    }
                    if (toolCall.function.arguments != null && toolCall.function.arguments.startsWith("\""))
                        toolCall.function.arguments = toolCall.function.arguments.substring(1, toolCall.function.arguments.length() - 1); // 不知道为什么DeepSeek喜欢在arg前后各加一个"，删了
                    LOGGER.debug(GSON.toJson(toolCall));
                    Message toolMsg = new Message();
                    toolMsg.tool_call_id = toolCall.id;
                    if (functionsMap.isEmpty()) {
                        LOGGER.error("LLMFunctions is null and LLM is trying to call an undefined function {}", toolCall.function.name);
                        toolMsg.content.add(new TextPiece("None function exist, stop calling functions."));
                        toolMsg(toolMsg);
                        next = true;
                        break; // 与原递归实现一致：无工具可用时跳过后续工具调用，直接进入下一轮
                    }
                    String toolName = toolCall.function.name;
                    LLMFunction llmFunction = functionsMap.get(toolName);
                    if (llmFunction == null) {
                        LOGGER.error("LLM is trying to call an undefined function {}", toolName);
                        toolMsg.content.add(new TextPiece("You're trying to call an undefined function " + toolName + "."));
                        toolMsg(toolMsg);
                        next = true;
                        break;
                    }

                    // 防死循环：单次回复内工具调用次数已达上限则不再执行，仅告知模型停止调用
                    if (calls >= maxCalls) {
                        LOGGER.warn("{} 单次回复内工具调用次数已达到上限({})，跳过执行工具 {}", model, maxCalls, toolName);
                        toolMsg.content.add(new TextPiece(
                                "你本次回复已经达到工具调用次数上限(" + maxCalls + ")，该调用已被忽略。" +
                                        "请立即停止调用任何工具，直接基于你已有的信息回答用户。如果信息不足，请如实说明。"));
                        toolMsg(toolMsg);
                        next = true;
                        continue;
                    }

                    // 防死循环：完全相同的参数不再重复执行，直接返回上次的结果
                    String callKey = toolName + "|" + normalizeArgs(toolCall.function.arguments);
                    if (exactResults.containsKey(callKey)) {
                        LOGGER.warn("{} 重复调用 {}，参数 {}", model, toolName, toolCall.function.arguments);
                        toolMsg.content.add(new TextPiece(
                                "你已经用完全相同的参数调用过工具 " + toolName + "，请不要重复调用！直接基于已有信息回答。\n上次结果：\n" +
                                        exactResults.get(callKey)));
                        toolMsg(toolMsg);
                        next = true;
                        continue;
                    }

                    JsonElement args = null;
                    if (toolCall.function.arguments != null) {
                        try {
                            args = JsonParser.parseString(toolCall.function.arguments);
                        } catch (JsonSyntaxException e) {
                            toolCall.function.arguments = toolCall.function.arguments.replaceAll("\\\\", ""); // 再捞一下 LLM 的零分试卷
                            try {
                                args = JsonParser.parseString(toolCall.function.arguments);
                            } catch (JsonSyntaxException ex) {
                                LOGGER.warn("Assistant 输出的参数 Gson 无法解析 {}", toolCall.function.arguments);
                                toolMsg.content.add(new TextPiece(ex.getMessage()));
                            }
                        }
                    } else {
                        LOGGER.warn("Assistant 返回的 tool_call {} 缺少 arguments", toolCall.id);
                        toolMsg.content.add(new TextPiece("工具调用缺少参数"));
                    }
                    if (args != null && args.isJsonObject()) {
                        request.placeholders.forEach(args.getAsJsonObject()::addProperty);
                        args.getAsJsonObject().addProperty("_ToolCallId", toolCall.id); // 供异步工具补投结果时使用
                    }
                    Message toolResponse;
                    if (args == null) {
                        toolResponse = new Message();
                        toolResponse.content.add(new TextPiece("未知原因的工具调用错误"));
                    } else try {
                        toolResponse = llmFunction.callback.callSync(args);
                    } catch (Exception e) {
                        toolResponse = new Message();
                        toolResponse.content.add(new TextPiece("调用工具失败: " + e.getMessage()));
                    }
                    if (toolResponse != null) {
                        calls++;
                        exactResults.put(callKey, toolResponse.toString());
                        next = true;
                        toolResponse.tool_call_id = toolCall.id; // 保证 tool 结果携带 tool_call_id，能完整进入下一轮请求
                        toolMsg = toolResponse;
                        if (toolResponse.asyncPending && request.asyncToolSink != null) {
                            request.asyncToolSink.onPendingResult(
                                    toolCall.id,
                                    llmFunction.asyncTimeoutMillis > 0 ? llmFunction.asyncTimeoutMillis : 0);
                        }
                    }
                    toolMsg(toolMsg);
                    LOGGER.info("{} 调用了 {}，参数 {}", model, toolName, toolCall.function.arguments);
                }
                if (!next) break;
                rounds--;
                continue;
            }
            LOGGER.error("出现意外导致请求未完成 finish_reason={}", finish);
            break;
        }
        return responding;
    }

    private static CompletionsResponse newFakeResponse() {
        CompletionsResponse responding = new CompletionsResponse();
        responding.usage.completion_tokens = 0;
        responding.usage.prompt_tokens = 0;
        responding.usage.total_tokens = 0;
        responding.choices = new CompletionsResponse.Choice[]{new CompletionsResponse.Choice() {{
            message.content = "";
            message.reasoning_content = "";
        }}};
        return responding;
    }

    private static String normalizeArgs(String args) {
        if (args == null) return "";
        return args.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 由底层会话重建请求体消息列表，等价于旧 OpenAIChannel 的构建逻辑 */
    @Override
    public List<Message> getMessageList() {
        List<Message> messages = new ArrayList<>();
        for (MCMessage m : base) {
            Message message = new Message();
            String role;
            if (assistantAccount != null && m.sender != null && m.sender.equals(assistantAccount)) {
                role = ROLE_ASSISTANT;
            } else if (m.getMetainfo(ROLE) instanceof String r) {
                role = r;
            } else {
                role = "user";
            }
            message.role = role;
            if (ROLE_ASSISTANT.equals(role)) {
                // 注意：不得用 if/else 短路掉 assistant 元信息的恢复，
                // 否则 assistant 的 tool_calls / reasoning 会在重建请求时丢失
                if (m.getMetainfo("reasoning") instanceof String reasoning) {
                    message.reasoning_content = reasoning;
                }
                if (m.getMetainfo("Tool_calls") instanceof Tool_call[] toolCalls) {
                    message.tool_calls = toolCalls;
                }
            } else if (ROLE_TOOL.equals(role)) {
                if (m.getMetainfo("tool_call_id") instanceof String toolCallId) {
                    message.tool_call_id = toolCallId;
                }
            }
            for (MsgField mf : m.messageFields) {
                if (mf instanceof ImageField imageField) {
                    message.content.add(new ImageUrlPiece(imageField.getUrl().toString()));
                } else {
                    message.content.add(new TextPiece(mf.toString()));
                }
            }
            messages.add(message);
        }
        if (systemPromptFirst != null) {
            Message first = new Message(systemPromptFirst);
            first.role = ROLE_SYSTEM;
            messages.add(0, first);
        }
        if (systemPromptLast != null) {
            Message last = new Message(systemPromptLast);
            last.role = ROLE_SYSTEM;
            messages.add(last);
        }
        return messages;
    }

    @Override
    public void userMsg(Message message) {
        base.add(toMCMessage(message, "user"));
    }

    @Override
    public void assistantMsg(Message message) {
        MCMessage mcm = toMCMessage(message, ROLE_ASSISTANT);
        if (message.reasoning_content != null) mcm.putMetainfo("reasoning", message.reasoning_content);
        if (message.tool_calls != null) mcm.putMetainfo("Tool_calls", message.tool_calls);
        base.add(mcm);
    }

    @Override
    public void toolMsg(Message message) {
        MCMessage mcm = toMCMessage(message, ROLE_TOOL);
        if (message.tool_call_id != null) mcm.putMetainfo("tool_call_id", message.tool_call_id);
        base.add(mcm);
    }

    /** 把 LLM 层的 Message 转换回 MCMessage 写回会话，等价于旧 OpenAIChannel 的写回逻辑 */
    private MCMessage toMCMessage(Message message, String role) {
        MCMessage mcm = new MCMessage();
        mcm.putMetainfo(ROLE, role);
        for (ContentPiece piece : message.content) {
            if (piece instanceof ImageUrlPiece imageUrlPiece) {
                try {
                    mcm.messageFields.add(new ImageField(new URL(imageUrlPiece.getUrl())));
                } catch (MalformedURLException e) {
                    mcm.messageFields.add(new TextField(imageUrlPiece.getUrl()));
                }
            } else if (piece instanceof TextPiece textPiece) {
                mcm.messageFields.add(new TextField(textPiece.getText()));
            } else {
                mcm.messageFields.add(new TextField(piece.toString()));
            }
        }
        return mcm;
    }
}
