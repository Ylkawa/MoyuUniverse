package com.nekoyu.Universe.API.Providers.LLMProvider;

import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ExtensionalArgs;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Assistant {
    LLMProvider provider;
    String model;
    String description;
    String systemPromptFirst;
    String systemPromptLast;
    boolean thinking;
    Map<String, LLMFunction> tools = new HashMap<>();

    public void setSystemPromptLast(String systemPromptLast) {
        this.systemPromptLast = systemPromptLast;
    }

    public Assistant(LLMProvider provider) {
        this.provider = provider;
    }

    public Assistant(LLMProvider provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    public CompletionsResponse completions(MessageList messageList, LLMProvider.BufferCallback bufferCallback) throws IOException {
        return completions(messageList, null, bufferCallback);
    }

    public CompletionsResponse completions(MessageList messageList, ExtensionalArgs extensionalArgs, LLMProvider.BufferCallback bufferCallback) throws IOException {
        if (extensionalArgs == null) extensionalArgs = new ExtensionalArgs();
        extensionalArgs.enable_thinking = thinking;
        extensionalArgs.systemPromptFirst = systemPromptFirst;
        extensionalArgs.systemPromptLast = systemPromptLast;
        return provider.completions(model, messageList, tools, extensionalArgs, bufferCallback);
    }

    public void addTool(LLMFunction tool) {
        tools.put(tool.name, tool);
    }

    public void setSystemPromptFirst(String systemPromptFirst) {
        this.systemPromptFirst = systemPromptFirst;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setThinking(boolean thinking) {
        this.thinking = thinking;
    }
}
