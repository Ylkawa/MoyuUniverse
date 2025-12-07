package com.nekoyu.Universe.API.Providers.LLMProvider;

import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;

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
        String forcedPrompt = """
                你必须严格按照以下规则调用工具（function）：
                1. function.arguments 必须是 JSON 对象，而不是字符串。
                   ❌ 错误: "{\\"url\\":\\"https://example.com\\"}"
                   ✔ 正确: {"url":"https://example.com"}
                2. 不要对 JSON 添加额外的引号。
                3. 不要对 JSON 进行转义，例如不要出现 \\" 或 \\\\。
                4. arguments 内必须是可被直接解析的 JSON，而不是字符串形式的 JSON。
                """;
        this.systemPrompt = systemPrompt + "\n\n" + forcedPrompt;
    }
}
