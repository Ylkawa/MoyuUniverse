package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.AIChat.Skill.SkillManager;
import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageChannel.MessageList;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.nekoyu.Universe.AIChat.AIChat.formatTimestamp;

public class Topic {
    AtomicBoolean responding = new AtomicBoolean(false);
    MessageList messages = new MessageList();
    List<MCMessage> systemPrompts = new ArrayList<>();
    SessionConfig sessionCfg;
    Map<UUID, Memory.Item> activatingMemory = new HashMap<>();
    SkillManager skillManager;

    private int promptFirstCount = 0;
    private int promptLastCount = 0;

    public Topic(SessionConfig sessionCfg) {
        this.sessionCfg = sessionCfg;
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
        messages.add(msg);
    }

    public void removeSystemPromptBySkillId(String skillId) {
        messages.removeIf(m -> skillId.equals(m.getMetainfo("skillId")));
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

        trimMessagesSafely();
    }

    private void trimMessagesSafely() {
        int budget = sessionCfg.maxTokens / 2;

        int total = 0;
        int keep = 0;
        int firstBlockSize = 0;
        boolean firstBlockSeen = false;

        for (int i = messages.size() - 1; i >= 0; ) {
            int blockTokens = 0;
            int blockSize = 0;

            MCMessage cur = messages.get(i);

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

        if (keep == 0 && !messages.isEmpty()) {
            keep = firstBlockSize;
        }

        if (messages.size() > 50 || total > budget || keep < messages.size()) {
            cleanPreservingSkills(keep);
        }
    }

    private void cleanPreservingSkills(int keep) {
        int removeCount = messages.size() - keep;
        if (removeCount <= 0) return;
        Iterator<MCMessage> it = messages.iterator();
        int removed = 0;
        while (it.hasNext() && removed < removeCount) {
            if (isSkillMessage(it.next())) continue;
            it.remove();
            removed++;
        }
    }

    private static boolean isSkillMessage(MCMessage m) {
        return m.getMetainfo("skillId") != null;
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

    public MessageList getOpenAIML() {
        MessageList full = new MessageList();
        for (int i = 0; i < promptFirstCount && i < systemPrompts.size(); i++) {
            full.add(copySystemMessage(systemPrompts.get(i)));
        }
        for (MCMessage m : messages) {
            full.add(isSkillMessage(m) ? copySystemMessage(m) : m);
        }
        for (int i = systemPrompts.size() - promptLastCount; i < systemPrompts.size(); i++) {
            full.add(copySystemMessage(systemPrompts.get(i)));
        }
        return full;
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
