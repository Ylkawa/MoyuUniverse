package com.nekoyu.Universe.API.Providers.LLMProvider;

import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.LLMFunction;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Assistant {
    LLMProvider provider;
    String model;
    String systemPrompt;
    Map<String, LLMFunction> tools = new HashMap<>();

    public Assistant(LLMProvider provider) {
        this.provider = provider;
    }

    public Assistant(LLMProvider provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    public CompletionsResponse completions(MessageList messageList, LLMProvider.BufferCallback bufferCallback) throws IOException {
        if (systemPrompt != null) messageList.add(0,
                new MCMessage.Builder()
                        .add(new TextField(systemPrompt))
                        .metainfo("role", "system")
                        .build()
        );
        return provider.completions(model, messageList, tools, bufferCallback);
    }

    public void addTool(LLMFunction tool) {
        tools.put(tool.name, tool);
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
