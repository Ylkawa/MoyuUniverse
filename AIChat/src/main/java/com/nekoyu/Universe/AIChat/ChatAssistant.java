package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Assistant;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ChatAssistant extends Assistant {
    private final LLMProvider provider;
    private final String model;
    private ChatContext chatContext = new ChatContext();
    private CompletionsRequest completionsRequest = new CompletionsRequest();
    private ToolLoopOptions toolLoopOptions = new ToolLoopOptions();
    private final List<LLMFunction> functions = new ArrayList<>();
    private String description;

    public ChatAssistant(LLMProvider provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    @Override
    public CompletionsResponse completions(LLMProvider.BufferCallback bufferCallback) throws IOException {
        // Tool/Function Calling 循环由 ChatContext 状态机驱动；provider 只做单次模型请求
        return chatContext.runTurn(provider, model, functions, completionsRequest, toolLoopOptions, bufferCallback);
    }

    public void addTool(LLMFunction tool) {
        functions.add(tool);
    }

    public void setThinking(boolean thinking) {
        completionsRequest.enable_thinking = thinking;
    }

    public void setSystemPromptFirst(String promptFirst) {
        chatContext.setSystemPromptFirst(promptFirst);
    }

    public void setSystemPromptLast(String promptLast) {
        chatContext.setSystemPromptLast(promptLast);
    }

    public void setChatContext(ChatContext chatContext) {
        this.chatContext = chatContext;
    }

    public ChatContext getChatContext() {
        return chatContext;
    }

    public void setCompletionsRequest(CompletionsRequest completionsRequest) {
        this.completionsRequest = completionsRequest;
    }

    public void setToolLoopOptions(ToolLoopOptions toolLoopOptions) {
        this.toolLoopOptions = toolLoopOptions == null ? new ToolLoopOptions() : toolLoopOptions;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
