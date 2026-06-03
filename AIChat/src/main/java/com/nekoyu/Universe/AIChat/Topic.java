package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageChannel.MessageList;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.nekoyu.Universe.AIChat.AIChat.formatTimestamp;

public class Topic {
    AtomicBoolean responding = new AtomicBoolean(false);
    MessageList messages = new MessageList();
    SessionConfig sessionCfg;
    Map<UUID, Memory.Item> activatingMemory = new HashMap<>();

    public Topic(SessionConfig sessionCfg) {
        this.sessionCfg = sessionCfg;
    }

    public void addMsg(MCMessage mcm) {
        MCMessage oaiM = new MCMessage();

        // 1) 先转换 role
        if (!Objects.equals(mcm.sender.getLocationId(), mcm.receiver.getLocationId())) {
            oaiM.messageFields.add(new TextField(
                    formatTimestamp((mcm.time * 1000)) +
                            "[" + mcm.id + "]" +
                            mcm.sender.getName() + "(" + mcm.sender.getLocationId() + ")" + mcm.sender.getSex() +
                            ": "
            ));
        }

        // 2) 追加消息内容
        if (sessionCfg.nativeImage) {
            for (MsgField mf : mcm.messageFields) {
                if (mf instanceof ImageField i) {
                    oaiM.messageFields.add(mf);
                    oaiM.messageFields.add(new TextField(i.solveMetadata()));
                } else if (mf instanceof TextField) {
                    oaiM.messageFields.add(mf);
                } else {
                    oaiM.messageFields.add(new TextField(mf.toString()));
                }
            }
        } else {
            oaiM.messageFields.add(new TextField(mcm.solveAll()));
        }

        oaiM.sender = mcm.sender;
        oaiM.sessionId = mcm.sessionId;
        messages.add(oaiM);

        // 3) 按“块”裁剪，避免截断 function calling 链
        trimMessagesSafely();
    }

    private void trimMessagesSafely() {
        int budget = sessionCfg.maxTokens / 2;

        int total = 0;          // 已保留 token
        int keep = 0;           // 最终保留消息数
        int firstBlockSize = 0;  // 保底：至少保留最后一个完整块
        boolean firstBlockSeen = false;

        for (int i = messages.size() - 1; i >= 0; ) {
            int blockTokens = 0;
            int blockSize = 0;

            MCMessage cur = messages.get(i);

            // tool block：tool + tool + tool ... + assistant(tool_calls)
            if (isToolMessage(cur)) {
                while (i >= 0 && isToolMessage(messages.get(i))) {
                    blockTokens += countTokens(messages.get(i));
                    blockSize++;
                    i--;
                }

                if (i >= 0 && isAssistantWithToolCalls(messages.get(i))) {
                    blockTokens += countTokens(messages.get(i));
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

        // 保底：如果预算太小导致一个块都放不下，至少保留最后一个完整块
        if (keep == 0 && messages.size() > 0) {
            keep = firstBlockSize;
        }

        // 只在需要时清理
        if (messages.size() > 50 || total > budget || keep < messages.size()) {
            messages.clean(keep);
        }
    }

    private static boolean isToolMessage(MCMessage m) {
        return "tool".equals(m.getMetainfo("role"));
    }

    private static boolean isAssistantWithToolCalls(MCMessage m) {
        return "assistant".equals(m.getMetainfo("role"))
                && m.getMetainfo("Tool_calls") != null;
    }

    // 估算某消息的tokens量
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

    public MessageList getOpenAIML() {
        return messages;
    }
}