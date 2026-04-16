package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.Providers.LLMProvider.Assistant;

import java.util.Objects;

import static com.nekoyu.Universe.AIChat.AIChat.formatTimestamp;

public class Topic {
    MessageList messages = new MessageList();
    SessionConfig sessionCfg;

    public Topic(SessionConfig sessionCfg) {
        this.sessionCfg = sessionCfg;
    }

    public void addMsg(MCMessage mcm) {
        MCMessage oaiM = new MCMessage();
        if (Objects.equals(mcm.sender.getLocationId(), mcm.receiver.getLocationId())) {
            oaiM.putMetainfo("role", "assistant");
        } else oaiM.putMetainfo("role", "user");
        oaiM.messageFields.add(new TextField(formatTimestamp((mcm.time * 1000)) + // [时间]
                "[" + mcm.id + "]" + // [时间] [消息id]
                mcm.sender.getName() + "(" + mcm.sender.getLocationId() + ")" + mcm.sender.getSex() + // [时间] [消息id] [昵称](用户QQ号)性别
                ": "));  // [时间] [消息id] [昵称](用户 LocationId)性别: [消息内容]

        if (sessionCfg.nativeImage) {
            for (MsgField mf : mcm.messageFields) {
                if (mf instanceof ImageField) oaiM.messageFields.add(mf);
                if (mf instanceof TextField) oaiM.messageFields.add(mf);
                else oaiM.messageFields.add(new TextField(mf.toString()));
            }
        } else oaiM.messageFields.add(new TextField(mcm.solveAll()));
        oaiM.sender = mcm.sender;
        messages.add(oaiM);

        int total = 0; // 总共的tokens数量（估算）
        int keep = 0; // 保留消息数量

        int budget = sessionCfg.maxTokens / 2;

        for (int i = messages.size() - 1; i >= 0; i--) {
            int t = countTokens(messages.get(i));

            if (total + t > budget) {
                break;
            }

            total += t;
            keep++;
        }
        // WARN keep可能为0，此时无法触发LLM回复

        if (messages.size() > 50 || total > budget) {
            messages.clean(keep);
        } // 上下文过长时强制清理
    }

    // 估算某消息的tokens量
    private static int countTokens(MCMessage oai) {
        int tokens = 0;
        for (MsgField mf : oai.messageFields) {
            if (mf instanceof TextField txtF) tokens+= txtF.toString().length() / 2;
            else if (mf instanceof ImageField) tokens+=1000;
            else tokens+=mf.toString().length();
        }
        return tokens;
    }

    public MessageList getOpenAIML() {
        return messages;
    }
}
