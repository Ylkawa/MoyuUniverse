package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Assistant;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMOptions;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;

import java.io.IOException;
import java.util.List;

public class ChatAssistant extends Assistant {
    private LLMProvider provider;
    private ChatContext chatContext;
    private LLMOptions llmOptions;
    private String model;
    private List<LLMFunction> functions;

    public ChatAssistant(LLMProvider provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    @Override
    public CompletionsResponse completions(LLMProvider.BufferCallback bufferCallback) throws IOException {
        provider.completions(model, chatContext, functions, llmOptions, bufferCallback);
    }

    public void setSystemPromptFirst(String promptFirst) {
        chatContext.systemPromptFirst = promptFirst;
    }

    public void setSystemPromptLast(String promptLast) {
        chatContext.systemPromptLast = promptLast;
    }

    public void setThinking(boolean thinking) {
        llmOptions.enable_thinking = thinking;
    }

    public void addTool(LLMFunction function) {
        functions.add(function);
    }
}
