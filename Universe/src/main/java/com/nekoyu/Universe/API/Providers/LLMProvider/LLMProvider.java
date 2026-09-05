package com.nekoyu.Universe.API.Providers.LLMProvider;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.API.Providers.Provider;

import java.io.IOException;
import java.util.List;

public abstract class LLMProvider extends Provider {
    /**
     * 请求一次模型并返回一次 Model Response。
     * <p>
     * Provider 只负责"协议传输"：
     * <ol>
     *     <li>把给定的消息序列化进请求体</li>
     *     <li>调用 LLM API</li>
     *     <li>解析 SSE / 响应</li>
     * </ol>
     * Provider 不驱动 Tool/Function Calling 循环，也不修改任何 Context 状态；
     * 是否执行工具、执行哪个工具、把结果写回 Context、是否再次请求模型，均由上层的 Context / Agent 状态机决定。
     *
     * @param model            模型名（可为 null，使用通道默认模型）
     * @param messages         本轮请求的消息序列
     * @param llmTools         本轮可用的工具列表（可为 null，表示不提供工具）
     * @param completionsRequest 中性请求选项（thinking / 占位符等）
     * @param bufferCallback   流式输出回调（可为 null）
     * @return 单轮 Model Response
     */
    public abstract CompletionsResponse completions(String model, List<Message> messages, List<LLMFunction> llmTools, CompletionsRequest completionsRequest, BufferCallback bufferCallback) throws IOException;

    public interface BufferCallback {
        /** 输出中，每接收到一行data就会把模型输出的词输出到这里 */
        void onCompletion(String outputs);
    }

}
