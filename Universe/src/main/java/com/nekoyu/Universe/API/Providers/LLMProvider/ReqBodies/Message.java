package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.ContentPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.TextPiece;

import java.util.LinkedList;

public class Message {
    public LinkedList<ContentPiece> content = new LinkedList<>();
    public String role;
    public String tool_call_id;
    public Tool_call[] tool_calls;
    public String reasoning_content;

    public Message() {}

    public Message(String text) {
        content.add(new TextPiece(text));
    }
}