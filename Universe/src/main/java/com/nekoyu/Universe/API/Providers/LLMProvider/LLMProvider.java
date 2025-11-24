package com.nekoyu.Universe.API.Providers.LLMProvider;

import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.API.Providers.Provider;

import java.io.IOException;
import java.util.Map;

public abstract class LLMProvider extends Provider {
    /**
     * 生成LLM回复
     */

    public CompletionsResponse completions(MessageList messageList, BufferCallback bufferCallback) throws IOException {
        return completions(null, messageList, null, bufferCallback);
    }
    public CompletionsResponse completions(String model, MessageList messageList, BufferCallback bufferCallback) throws IOException {
        return completions(model, messageList, null, bufferCallback);
    }
    public abstract CompletionsResponse completions(String model, MessageList messageList, Map<String, LLMFunction> llmTools, BufferCallback bufferCallback) throws IOException;

    public interface BufferCallback {
        /** 输出中，每接收到一行data就会把模型输出的词输出到这里 */
        void onCompletion(String outputs);
    }

    public Assistant newAssistant() {
        return new Assistant(this);
    }
}
