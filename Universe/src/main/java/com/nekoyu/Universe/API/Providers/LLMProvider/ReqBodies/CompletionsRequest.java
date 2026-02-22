package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

public class CompletionsRequest {
    public String model;
    public boolean stream;
    public List<Message> messages;
    public List<LLMTool> tools;
    public HashMap<String, Object> stream_options;
    public boolean enable_thinking;

    public CompletionsRequest() {
        messages = new LinkedList<>();
        tools = new LinkedList<>();
        stream_options = new HashMap<>();
    }
}
