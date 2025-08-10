package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event;

import java.util.List;
import java.util.Map;

public class Message {
    public long time;
    public long self_id;
    public String post_type;
    public String message_type;
    public String sub_type;
    public int message_id;
    public long user_id;
    public List<MessageSegment> message;
    public String raw_message;
    public int font;
    public Sender sender;
    public long group_id;
    public Anonymous anonymous;

    private transient String stringMsg = null;

    private class MessageSegment {
        public String type;
        public Map<String, String> data;
    }

    public String getMessageString() {
        if (stringMsg != null) return stringMsg;
        StringBuilder msg = new StringBuilder();
        for (MessageSegment ms : message) {
            switch (ms.type) {
                case "text":
                    msg.append(ms.data.get("text"));
                    break;
                case "face":
                    msg.append("[QQ表情]");
                    break;
                    // 暂时没看到有能和emoji一一对应的表格，先不管
                case "image":
                    msg.append("[图片]");
                    break;
                    // 放不进去文本，先这样
                case "record":
                    msg.append("[语音]");
                    break;
                case "video":
                    msg.append("[视频]");
                    break;
                case "at":
                    msg.append("[@qq:user/").append(ms.data.get("qq")).append("]");
                    break;
                case "rps":
                    msg.append("[猜拳魔法表情]");
                    break;
                case "dice":
                    msg.append("[掷骰子魔法表情]");
                    break;
                case "shake":
                    msg.append("[窗口抖动]");
                    break;
                case "poke":
                    msg.append("[戳一戳]");
                    break;
                case "anonymous":
                    msg.append("[匿名消息]");
                    break;
                case "share":
                    msg.append("[分享链接, ").append(ms.data.get("title")).append(" : ").append(ms.data.get("url")).append(" ]");
                    break;
                case "contact":
                    switch (ms.data.get("type")) {
                        case "qq":
                            msg.append("[分享QQ群, ");
                            break;
                        case "group":
                            msg.append("[分享QQ好友, ");
                            break;
                    }
                    msg.append(ms.data.get("id")).append("]");
                    break;
                case "location":
                    msg.append("[分享一处位置, ").append("纬度").append(ms.data.get("lat")).append(", 经度").append(ms.data.get("lon"));
                    break;
                case "music": // 音乐分享，普通的卡片要和自定义的卡片分开讨论
                    switch (ms.data.get("type")) {
                        case "163":
                            msg.append("[网易云音乐, ").append(ms.data.get("id")).append("]");
                            break;
                        case "qq":
                            msg.append("[QQ音乐, ").append(ms.data.get("id")).append("]");
                            break;
                        case "xm":
                            msg.append("[虾米音乐, ").append(ms.data.get("id")).append("]");
                            break;
                        case "custom":
                            msg.append("[音乐分享, ").append(ms.data.get("title")).append(", ").append(ms.data.get("url"));
                            break;
                    }
                    break;
                case "reply":
                    msg.append("[回复消息 ").append(ms.data.get("id")).append("]");
                    break;
                case "forward":
                    msg.append("[合并转发, ").append(ms.data.get("id")).append("]");
                    break;
                case "node":
                    msg.append("[合并转发节点, ").append(ms.data.get("id")).append("]");
                    break;
                // 合并转发自定义节点 没做
                case "xml":
                    msg.append("[XML消息]");
                    break;
                case "json":
                    msg.append("[JSON消息]");
                    break;
            }
        }
        stringMsg = msg.toString();
        return stringMsg;
    }
}
