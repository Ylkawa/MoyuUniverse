package com.nekoyu.Universe.API.Providers.LLMProvider;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Context;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.Provider;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public abstract class LLMProvider extends Provider {
    /**
     * 生成 LLM 回复
     */
    public abstract CompletionsResponse completions(String model, Context context, List<LLMFunction> llmTools, CompletionsRequest completionsRequest, BufferCallback bufferCallback) throws IOException;

    public interface BufferCallback {
        /** 输出中，每接收到一行data就会把模型输出的词输出到这里 */
        void onCompletion(String outputs);
    }

    /** 是否启用跨请求后台异步工具结果 */
    public boolean asyncToolsEnabled() {
        return false;
    }

    /** 异步工具结果默认最大等待时间（毫秒） */
    public long asyncMaxWaitMillis() {
        return 30_000;
    }

    /** 新消息静默去抖时长（毫秒） */
    public long asyncDebounceMillis() {
        return 5_000;
    }
}
