package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.Universe;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
        int solvedPic = 0;
        List<AtomicBoolean> flags = new ArrayList<>();
        // 逆序遍历
        for (int i = messageFields.size() - 1; i >= 0; i--) {
            MsgField msgField = messageFields.get(i);
            switch (msgField.type) {
                case "image" -> {
                    if (!msgField.isSolved && solvedPic < 3) {
                        AtomicBoolean flag = new AtomicBoolean(false);
                        flags.add(flag);
                        new Thread(() -> msgField.solve(flag)).start(); // 这里做成堵塞式的了，影响性能，到时候要改成同时解析
                        solvedPic++;
                    }
                }
            }
        }
        boolean continueFlag = false;
        while (!continueFlag) {
            continueFlag = true;
            for (AtomicBoolean flag : flags) {
                if (!flag.get()) {
                    continueFlag = false;
                    Thread.yield();
                    break;
                }
            }
        }
        for (MsgField msgField: messageFields) {
            sb.append(msgField.getAsString());
        }
        return sb.toString();
    }

    public void reply(String message) {
        Universe.MessageChannelManager.sendMessage(sessionId, message);
    }
}
