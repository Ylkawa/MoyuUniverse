package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.FileField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.ContentPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.ImageUrlPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.TextPiece;

import java.util.LinkedList;

public class Message {
    public LinkedList<ContentPiece> content = new LinkedList<>();
    public String role;
    public String tool_call_id;
    public Tool_call[] tool_calls;
    public String reasoning_content;
    /** 隐式思考模型的加密思维链（百炼 qwen4-max 的 encrypted_content），必须随所属 assistant 消息原样回传，不得展示或改写 */
    public String encrypted_content;
    /** 厂商扩展字段（如 Gemini message 级 thought_signature）；null 时不序列化，回传时原样保留 */
    public ExtraContent extra_content;
    /** 工具回调返回 true 表示该调用为异步未决：该次调用的真实结果稍后通过会话补投 */
    public transient boolean asyncPending;
    /** 将工具结果写回会话期间持有文件对象，避免缓存被提前清理。 */
    public transient LinkedList<FileField> retainedFiles = new LinkedList<>();

    public Message() {
    }

    public Message(String text) {
        content.add(new TextPiece(text));
    }

    public static Message from(MFChain mfChain) {
        Message message = new Message();
        for (MsgField field : mfChain) {
            if (field instanceof FileField fileField) message.retainedFiles.add(fileField);
            if (field instanceof ImageField imageField) {
                message.content.add(new ImageUrlPiece(imageField.getUrl().toString()));
            } else {
                message.content.add(new TextPiece(field.toString()));
            }
        }
        return message;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (ContentPiece piece : content) {
            if (piece instanceof TextPiece textPiece) {
                sb.append(textPiece.getText());
            } else if (piece instanceof ImageUrlPiece imageUrlPiece) {
                sb.append(imageUrlPiece.getUrl());
            }
        }
        return sb.toString();
    }
}
