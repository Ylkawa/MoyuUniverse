package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.Account;
import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.ContentPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.ImageUrlPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Context;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.TextPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Tool_call;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ChatContext implements Context {
    private static final String ROLE = "role";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_TOOL = "tool";
    private static final String ROLE_SYSTEM = "system";

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

    /** 由底层会话重建请求体消息列表，等价于旧 OpenAIChannel 的构建逻辑 */
    @Override
    public List<Message> getMessageList() {
        List<Message> messages = new ArrayList<>();
        for (MCMessage m : base) {
            Message message = new Message();
            if (assistantAccount != null && m.sender != null && m.sender.equals(assistantAccount)) {
                message.role = ROLE_ASSISTANT;
            } else if (m.getMetainfo(ROLE) instanceof String role) {
                message.role = role;
                if (role.equals(ROLE_ASSISTANT) && m.getMetainfo("Tool_calls") instanceof Tool_call[] toolCalls) {
                    if (m.getMetainfo("reasoning") instanceof String reasoning) {
                        message.reasoning_content = reasoning;
                    }
                    message.tool_calls = toolCalls;
                }
                if (role.equals(ROLE_TOOL) && m.getMetainfo("tool_call_id") instanceof String toolCallId) {
                    message.tool_call_id = toolCallId;
                }
            } else {
                message.role = "user";
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