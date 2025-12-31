package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.Universe;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MCMessage {
    public Account receiver;
    public Account sender;
    public String messageString;
    public String sessionId; // 这个sessionId应该是Global SessionId
    public long time;
    public long id;
    public LinkedList<MsgField> messageFields;
    public int level;
    public Map<String, Object> metainfo = new HashMap<>();
    public boolean isRecalled = false;
    /**
     * if send by universe
     */
    public boolean universe = false;

    public MCMessage() {
        sender = new Account();
        receiver = new Account();
        messageFields = new LinkedList<>();
        level = 0;
    }

    public static Builder Builder() {
        return new Builder();
    }

    /**
     * 将本段所有段落全部整合到同一个字符串内
     *
     * @return 此条消息内容（纯文本形式）
     */
    public String solveAll() {
        var sb = new StringBuilder();
        int solvedPic = 0;
        ExecutorService executor = Executors.newCachedThreadPool();
        // 逆序遍历
        for (int i = messageFields.size() - 1; i >= 0; i--) {
            MsgField msgField = messageFields.get(i);
            switch (msgField.type) {
                case "image" -> {
                    if (!msgField.isSolved && solvedPic < 3) {
                        executor.execute(() -> {
                            try {
                                msgField.solve();
                            } catch (Exception ignored) {
                            }
                        });
                        solvedPic++;
                    }
                }
                case "voice" -> msgField.solve();
            }
        }
        executor.shutdown();
        try {
            executor.awaitTermination(60, TimeUnit.SECONDS);
            for (MsgField msgField : messageFields) {
                sb.append(msgField.getAsString());
            }
            return sb.toString();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public String getLocationId() {
        return sender.platform + ":" + sessionId.split(":")[1];
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

    public static class Builder {
        MCMessage msg = new MCMessage();

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
