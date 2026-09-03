package com.nekoyu.universe.openaiadapter.RequestBodies;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;

import java.util.LinkedList;
import java.util.List;

public class OpenAICompletionsRequest {
    public String model;
    public boolean stream;
    public List<Message> messages;
    public List<LLMFunction> tools;

    public OpenAICompletionsRequest() {
        messages = new LinkedList<>();
        tools = new LinkedList<>();
    }
}
