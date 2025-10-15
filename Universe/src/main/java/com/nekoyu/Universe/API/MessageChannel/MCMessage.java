package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.Universe;

import java.util.LinkedList;

public class MCMessage {
    public Account receiver;
    public Account sender;
    public String messageString;
    public String sessionId; // 这个sessionId应该是Global SessionId
    public long time;
    public int id;
    public LinkedList<MsgField> messageFields;
    public int level;

    public MCMessage() {
        sender = new Account();
        receiver = new Account();
        messageFields = new LinkedList<>();
        level = 0;
    }

    /**
     * 将本段所有段落全部整合到同一个字符串内
     * @return 此条消息内容（纯文本形式）
     */
    public String solveAll() {
        var sb = new StringBuilder();
        for (MsgField msgField: messageFields) {
            sb.append(msgField.getAsString());
        }
        return sb.toString();
    }

    public void reply(String message) {
        Universe.MessageChannelManager.sendMessage(sessionId, message);
    }
}
