package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
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

    public Message() {
    }

    public Message(String text) {
        content.add(new TextPiece(text));
    }

    public static Message from(MFChain mfChain) {
        Message message = new Message();
        for (MsgField field : mfChain) {
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