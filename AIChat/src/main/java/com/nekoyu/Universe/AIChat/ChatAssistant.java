package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Assistant;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChatAssistant extends Assistant {
    private final LLMProvider provider;
    private final String model;
    private ChatContext chatContext = new ChatContext();
    private CompletionsRequest completionsRequest = new CompletionsRequest();
    private ToolLoopOptions toolLoopOptions = new ToolLoopOptions();
    private final List<LLMFunction> functions = new ArrayList<>();
    /** 已加入工具的名字集合：按 function name 去重，保持稳定顺序，避免向 API 下发重复工具定义 */
    private final Set<String> functionNames = new HashSet<>();
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

    /**
     * 加入一个工具，按 function name 自动去重：同名工具只保留首次加入者。
     * 既避免向 OpenAI 兼容 API 下发重复定义（会被拒绝），也保证 tools 数组顺序稳定，利于上下文前缀缓存。
     */
    public void addTool(LLMFunction tool) {
        if (tool == null || tool.name == null) return;
        if (functionNames.add(tool.name)) {
            functions.add(tool);
        }
    }

    /** 批量加入工具（同样按 name 去重，null 安全） */
    public void addTools(Collection<LLMFunction> tools) {
        if (tools == null) return;
        for (LLMFunction tool : tools) addTool(tool);
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
