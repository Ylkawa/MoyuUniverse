package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.AIChat.Skill.SkillManager;
import com.nekoyu.Universe.API.MessageChannel.Account;
import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageField.AtField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Tool_call;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.nekoyu.Universe.AIChat.AIChat.formatTimestamp;

public class Topic {
    AtomicBoolean responding = new AtomicBoolean(false);
    /** 底层会话消息统一由 ChatContext 管理 */
    private final ChatContext chatContext = new ChatContext();
    List<MCMessage> systemPrompts = new ArrayList<>();
    SessionConfig sessionCfg;
    private final ToolLoopOptions toolLoopOptions;
    Map<UUID, Memory.Item> activatingMemory = new HashMap<>();
    SkillManager skillManager;
    /** 本会话使用的 LLMProvider */
    LLMProvider provider;
    /** 最近一条触发回复的用户消息，用于异步结果补回复时的回话目标 */
    volatile MCMessage lastTrigger;

    /** 异步未决的工具调用：tool_call_id -> 未决记录 */
    public static class PendingCall {
        public final long deadline;            // 到期时间戳（毫秒）
        public final String toolName;
        public volatile boolean responded = false;

        public PendingCall(long deadline, String toolName) {
            this.deadline = deadline;
            this.toolName = toolName;
        }
    }

    final Map<String, PendingCall> pendingCalls = new ConcurrentHashMap<>();

    private int promptFirstCount = 0;
    private int promptLastCount = 0;

    public Topic(SessionConfig sessionCfg) {
        this.sessionCfg = sessionCfg;
        this.toolLoopOptions = sessionCfg.ToolLoop == null ? new ToolLoopOptions() : sessionCfg.ToolLoop;
    }

    public ToolLoopOptions getToolLoopOptions() {
        return toolLoopOptions;
    }

    public void initSystemPrompts(String promptFirstGlobal, String promptFirstSession,
                                   String promptLastSession, String promptLastGlobal) {
        systemPrompts.clear();
        promptFirstCount = 0;
        promptLastCount = 0;

        if (promptFirstGlobal != null && !promptFirstGlobal.isEmpty()) {
            systemPrompts.add(createSystemMessage(promptFirstGlobal));
            promptFirstCount++;
        }
        if (promptFirstSession != null && !promptFirstSession.isEmpty()) {
            systemPrompts.add(createSystemMessage(promptFirstSession));
            promptFirstCount++;
        }

        if (promptLastSession != null && !promptLastSession.isEmpty()) {
            systemPrompts.add(createSystemMessage(promptLastSession));
            promptLastCount++;
        }
        if (promptLastGlobal != null && !promptLastGlobal.isEmpty()) {
            systemPrompts.add(createSystemMessage(promptLastGlobal));
            promptLastCount++;
        }
    }

    public void addSkillMessage(MCMessage msg) {
        chatContext.getBase().add(msg);
    }

    public void removeSystemPromptBySkillId(String skillId) {
        chatContext.getBase().removeIf(m -> skillId.equals(m.getMetainfo("skillId")));
    }

    /** 从底层会话记录恢复历史消息 */
    public void addHistory(MessageList history) {
        if (history == null) return;
        for (MCMessage m : history) {
            addMsg(m);
        }
    }

    private MCMessage createSystemMessage(String content) {
        MCMessage msg = new MCMessage();
        msg.putMetainfo("role", "system");
        msg.messageFields.add(new TextField(content));
        return msg;
    }

    public void addMsg(MCMessage mcm) {
        MCMessage oaiM = new MCMessage();

        if (!Objects.equals(mcm.sender.getLocationId(), mcm.receiver.getLocationId())) {
            oaiM.messageFields.add(new TextField(
                    formatTimestamp((mcm.time * 1000)) +
                            "[" + mcm.id + "]" +
                            mcm.sender.getName() + "(" + mcm.sender.getLocationId() + ")" + mcm.sender.getSex() +
                            ": "
            ));
        }

        if (sessionCfg.nativeImage) {
            for (MsgField mf : mcm.messageFields) {
                if (mf instanceof ImageField i) {
                    oaiM.messageFields.add(mf);
                    oaiM.messageFields.add(new TextField(i.solveMetadata()));
                } else if (mf instanceof TextField) {
                    oaiM.messageFields.add(mf);
                } else if (mf instanceof AtField atField) {
                    oaiM.messageFields.add(new TextField("<@:" + atField.target.getLocationId() + ">"));
                } else {
                    oaiM.messageFields.add(new TextField(mf.toString()));
                }
            }
        } else {
            oaiM.messageFields.add(new TextField(mcm.solveAll()));
        }

        oaiM.sender = mcm.sender;
        oaiM.sessionId = mcm.sessionId;
        chatContext.getBase().add(oaiM);

        trimMessagesSafely();
    }

    public List<String> getLocationIds() {
        return chatContext.getBase().getLocationIds();
    }

    public int getMessageCount() {
        return chatContext.getBase().size();
    }

    public MCMessage getMessage(int index) {
        return chatContext.getBase().get(index);
    }

    /** 当前会话的完整聊天上下文 */
    public ChatContext getChatContext() {
        return chatContext;
    }

    /** 基于当前会话消息构建一个独立的 ChatContext 副本，供记忆构建等场景使用，与实时会话互不影响 */
    public ChatContext createChatContextCopy() {
        MessageList copy = new MessageList();
        copy.addAll(chatContext.getBase());
        return new ChatContext(copy);
    }

    /** 构建本次请求的完整上下文：开头的 system prompts + 会话消息 + 末尾的 system prompts */
    public ChatContext getOpenAIContext(Account assistantAccount) {
        MessageList full = new MessageList();
        for (int i = 0; i < promptFirstCount && i < systemPrompts.size(); i++) {
            full.add(copySystemMessage(systemPrompts.get(i)));
        }
        for (MCMessage m : chatContext.getBase()) {
            full.add(isSkillMessage(m) ? copySystemMessage(m) : m);
        }
        for (int i = systemPrompts.size() - promptLastCount; i < systemPrompts.size(); i++) {
            full.add(copySystemMessage(systemPrompts.get(i)));
        }
        return new ChatContext(full, assistantAccount);
    }

    /**
     * 注册一个异步未决的工具调用。
     *
     * @param toolCallId    该次调用ID
     * @param timeoutMillis 该调用允许等待的最大时长（毫秒）
     */
    public void registerPendingCall(String toolCallId, long timeoutMillis) {
        pendingCalls.put(toolCallId, new PendingCall(System.currentTimeMillis() + timeoutMillis, null));
    }

    public boolean hasPendingCalls() {
        return !pendingCalls.isEmpty();
    }

    /**
     * 提交异步工具结果：追加一条 tool 消息到会话上下文，并标记该 tool_call 已回应。
     *
     * @return 是否成功追加（该 tool_call 存在且未超时丢弃）
     */
    public boolean submitAsyncResult(String toolCallId, String text) {
        PendingCall call = pendingCalls.get(toolCallId);
        if (call == null) return false;
        call.responded = true;
        MCMessage toolMsg = appendToolMessage(toolCallId, text);
        trimMessagesSafely();
        chatContext.enqueuePendingMessage(toolMsg);
        return true;
    }

    /**
     * 向会话上下文追加一条 tool 消息，插入到对应 assistant(tool_calls) 的紧随工具块之后，
     * 以保证与 OpenAI 兼容 API 的消息顺序合法。
     */
    public MCMessage appendToolMessage(String toolCallId, String text) {
        int insertPos = chatContext.getBase().size();
        int anchor = findAssistantToolCallsIndex(toolCallId);
        if (anchor >= 0) {
            int pos = anchor + 1;
            int newCallIndex = findToolCallIndex(chatContext.getBase().get(anchor), toolCallId);
            while (pos < chatContext.getBase().size() && isToolMessage(chatContext.getBase().get(pos))) {
                String existingId = (String) chatContext.getBase().get(pos).getMetainfo("tool_call_id");
                int existingIndex = findToolCallIndex(chatContext.getBase().get(anchor), existingId);
                if (newCallIndex >= 0 && existingIndex >= 0 && existingIndex > newCallIndex) break;
                pos++;
            }
            insertPos = pos;
        }
        MCMessage toolMsg = new MCMessage();
        toolMsg.putMetainfo("role", "tool");
        toolMsg.putMetainfo("tool_call_id", toolCallId);
        toolMsg.messageFields.add(new TextField(text));
        chatContext.getBase().add(insertPos, toolMsg);
        return toolMsg;
    }

    private int findAssistantToolCallsIndex(String toolCallId) {
        for (int i = chatContext.getBase().size() - 1; i >= 0; i--) {
            MCMessage m = chatContext.getBase().get(i);
            Object role = m.getMetainfo("role");
            if (role instanceof String r && r.equals("assistant")) {
                Object tc = m.getMetainfo("Tool_calls");
                if (tc instanceof Tool_call[] calls) {
                    for (Tool_call c : calls) {
                        if (toolCallId.equals(c.id)) return i;
                    }
                }
            }
        }
        return -1;
    }

    private static int findToolCallIndex(MCMessage assistant, String toolCallId) {
        if (toolCallId == null) return -1;
        Object tc = assistant.getMetainfo("Tool_calls");
        if (tc instanceof Tool_call[] calls) {
            for (Tool_call call : calls) {
                if (toolCallId.equals(call.id)) return call.index;
            }
        }
        return -1;
    }

    public boolean allPendingsResponded() {
        for (PendingCall call : pendingCalls.values()) {
            if (!call.responded) return false;
        }
        return true;
    }

    /** 移除并返回已超时且仍未回应的未决 tool_call_id 列表 */
    public List<String> expireOverduePendings() {
        long now = System.currentTimeMillis();
        List<String> overdue = new ArrayList<>();
        for (Map.Entry<String, PendingCall> e : pendingCalls.entrySet()) {
            if (!e.getValue().responded && e.getValue().deadline < now) overdue.add(e.getKey());
        }
        overdue.forEach(pendingCalls::remove);
        return overdue;
    }

    /** 尚未回应的未决中最早的截止时间；无则返回 -1 */
    public long pendingEarliestDeadline() {
        long earliest = Long.MAX_VALUE;
        boolean found = false;
        for (PendingCall call : pendingCalls.values()) {
            if (!call.responded) {
                earliest = Math.min(earliest, call.deadline);
                found = true;
            }
        }
        return found ? earliest : -1;
    }

    /** 回合已消费完毕，移除这些未决记录（释放清理保护） */
    public void removeConsumedPendingCalls(Set<String> consumed) {
        consumed.forEach(pendingCalls::remove);
    }

    /** 会话关闭时清空全部未决记录 */
    public void clearPendingCalls() {
        pendingCalls.clear();
    }

    private void trimMessagesSafely() {
        int budget = sessionCfg.maxTokens / 2;

        int total = 0;
        int keep = 0;
        int firstBlockSize = 0;
        boolean firstBlockSeen = false;

        for (int i = chatContext.getBase().size() - 1; i >= 0; ) {
            int blockTokens = 0;
            int blockSize = 0;

            MCMessage cur = chatContext.getBase().get(i);

            if (isToolMessage(cur)) {
                while (i >= 0 && isToolMessage(chatContext.getBase().get(i))) {
                    blockTokens += countTokens(chatContext.getBase().get(i));
                    blockSize++;
                    i--;
                }

                if (i >= 0 && isAssistantWithToolCalls(chatContext.getBase().get(i))) {
                    blockTokens += countTokens(chatContext.getBase().get(i));
                    blockSize++;
                    i--;
                }
            } else {
                blockTokens = countTokens(cur);
                blockSize = 1;
                i--;
            }

            if (!firstBlockSeen) {
                firstBlockSeen = true;
                firstBlockSize = blockSize;
            }

            if (total + blockTokens > budget) {
                break;
            }

            total += blockTokens;
            keep += blockSize;
        }

        if (keep == 0 && !chatContext.getBase().isEmpty()) {
            keep = firstBlockSize;
        }

        if (chatContext.getBase().size() > 50 || total > budget || keep < chatContext.getBase().size()) {
            cleanPreservingSkills(keep);
        }
    }

    private void cleanPreservingSkills(int keep) {
        int removeCount = chatContext.getBase().size() - keep;
        if (removeCount <= 0) return;
        Iterator<MCMessage> it = chatContext.getBase().iterator();
        int removed = 0;
        while (it.hasNext() && removed < removeCount) {
            MCMessage msg = it.next();
            if (isSkillMessage(msg) || isPendingProtected(msg)) continue;
            it.remove();
            removed++;
        }
    }

    private static boolean isSkillMessage(MCMessage m) {
        return m.getMetainfo("skillId") != null;
    }

    /** 未决异步调用轮次内的消息（assistant 的 tool_calls 与其 tool 消息）在未决期间不被清理 */
    private boolean isPendingProtected(MCMessage m) {
        if (pendingCalls.isEmpty()) return false;
        Object role = m.getMetainfo("role");
        if (role instanceof String r && r.equals("tool")) {
            Object id = m.getMetainfo("tool_call_id");
            return id != null && pendingCalls.containsKey(id.toString());
        }
        if (role instanceof String r && r.equals("assistant")) {
            Object tc = m.getMetainfo("Tool_calls");
            if (tc instanceof Tool_call[] calls) {
                for (Tool_call c : calls) {
                    if (c.id != null && pendingCalls.containsKey(c.id)) return true;
                }
            }
        }
        return false;
    }

    private static boolean isToolMessage(MCMessage m) {
        return "tool".equals(m.getMetainfo("role"));
    }

    private static boolean isAssistantWithToolCalls(MCMessage m) {
        return "assistant".equals(m.getMetainfo("role"))
                && m.getMetainfo("Tool_calls") != null;
    }

    private static int countTokens(MCMessage oai) {
        int tokens = 0;
        for (MsgField mf : oai.messageFields) {
            if (mf instanceof TextField txtF) {
                tokens += txtF.toString().length() / 2;
            } else if (mf instanceof ImageField) {
                tokens += 1000;
            } else {
                tokens += mf.toString().length();
            }
        }
        return tokens;
    }

    private MCMessage copySystemMessage(MCMessage sysMsg) {
        MCMessage copy = new MCMessage();
        copy.putMetainfo("role", sysMsg.getMetainfo("role"));
        Object skillId = sysMsg.getMetainfo("skillId");
        if (skillId != null) {
            copy.putMetainfo("skillId", skillId);
        }
        for (var field : sysMsg.messageFields) {
            copy.messageFields.add(field);
        }
        return copy;
    }
}
