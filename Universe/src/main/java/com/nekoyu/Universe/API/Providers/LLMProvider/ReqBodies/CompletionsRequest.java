package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import java.util.LinkedList;
import java.util.List;

public class CompletionsRequest {
    public String model;
    public boolean stream;
    public List<Message> messages;
    public List<LLMFunction> tools;

    public CompletionsRequest() {
        messages = new LinkedList<>();
        tools = new LinkedList<>();
    }
}
