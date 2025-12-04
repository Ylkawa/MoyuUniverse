package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.nekoyu.Universe.API.Providers.LLMProvider.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.LLMTool;

import java.util.LinkedList;
import java.util.List;

public class CompletionsRequest {
    public String model;
    public boolean stream;
    public List<Message> messages;
    public List<LLMTool> tools;

    public CompletionsRequest() {
        messages = new LinkedList<>();
        tools = new LinkedList<>();
    }
}
