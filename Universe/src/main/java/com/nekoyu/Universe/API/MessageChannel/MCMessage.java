package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.Universe;

import java.util.*;
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
    public Map<String, Object> metainfo = new HashMap<>();
    /** if send by universe */
    public boolean universe = false;

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
                        new Thread(() -> {
                            try {
                                msgField.solve(flag);
                            } catch (Exception e) {
                                flag.set(true);
                            }
                        }).start(); // 这里做成堵塞式的了，影响性能，到时候要改成同时解析
                        solvedPic++;
                    }
                }
                case "voice" -> msgField.solve();
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

    public Object getMetainfo(String key) {
        return metainfo.get(key);
    }

    public void putMetainfo(String key, Object value) {
        metainfo.put(key, value);
    }

    public static Builder Builder() {
        return new Builder();
    }
    public static class Builder {
        MCMessage msg;
        public Builder add(MsgField msgField) {
            msg.messageFields.add(msgField);
            return this;
        }
        public Builder receiverAccount(Account account) {
            msg.receiver = account;
            return this;
        }
        public Builder senderAccount(Account account) {
            msg.sender = account;
            return this;
        }
        public Builder sessionId(String sessionId) {
            msg.sessionId = sessionId;
            return this;
        }
        public Builder time(long time) {
            msg.time = time;
            return this;
        }
        public Builder id(int id) {
            msg.id = id;
            return this;
        }
        public Builder level(int level) {
            msg.level = level;
            return this;
        }
        public Builder universe(boolean universe) {
            msg.universe = universe;
            return this;
        }
        public Builder metainfo(String key, Object value) {
            msg.metainfo.put(key, value);
            return this;
        }
        public MCMessage build() {
            return msg;
        }
    }
}
