package com.nekoyu.Universe.AIChat.knowledge;

import com.nekoyu.Universe.AIChat.ChatContext;
import com.nekoyu.Universe.AIChat.StructuredOutputs;
import com.nekoyu.Universe.AIChat.ToolLoopOptions;

import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ResponseFormat;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Tool_call;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 模拟工具调用轮次和最终回复，无需模型或数据库。 */
public class StructuredTurnTest {
    private static final String INTERMEDIATE = "{\"sources\":[]}";
    private static final String FINAL = "{\"sources\":[{\"url\":\"https://example.com\",\"title\":\"hello world\",\"content\":\"summary\"}]}";

    public static void main(String[] args) throws Exception {
        run(StructuredOutputs.ingestion(), false);
        run(ResponseFormat.jsonObject(), false);
        run(null, false);
        run(StructuredOutputs.ingestion(), true);
        System.out.println("StructuredTurnTest passed");
    }

    private static void run(ResponseFormat format, boolean truncate) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        ChatContext context = new ChatContext();
        context.userMsg(new Message("question"));
        LLMFunction tool = LLMFunction.builder().name("lookup")
                .callback((LLMFunction.SyncCallback) args -> {
                    executed.incrementAndGet();
                    return new Message("tool result");
                }).build();
        CompletionsRequest request = new CompletionsRequest();
        request.responseFormat = format;
        LLMProvider provider = new LLMProvider() {
            @Override
            public CompletionsResponse completions(String model, List<Message> messages, List<LLMFunction> tools,
                                                  CompletionsRequest options, BufferCallback callback) {
                check(options.responseFormat == format, "retain the declaration across tool rounds");
                int round = calls.incrementAndGet();
                CompletionsResponse response = new CompletionsResponse();
                response.choices = new CompletionsResponse.Choice[]{new CompletionsResponse.Choice()};
                response.usage.prompt_tokens = 2;
                response.usage.completion_tokens = 3;
                response.usage.total_tokens = 5;
                var choice = response.choices[0];
                if (round == 1) {
                    choice.message.content = INTERMEDIATE;
                    choice.finish_reason = "tool_calls";
                    Tool_call call = new Tool_call();
                    call.id = "call_1";
                    call.type = "function";
                    call.function.name = "lookup";
                    call.function.arguments = "{}";
                    choice.message.tool_calls = new Tool_call[]{call};
                    if (callback != null) callback.onCompletion(INTERMEDIATE);
                } else {
                    check(messages.stream().anyMatch(m -> "tool".equals(m.role) && "call_1".equals(m.tool_call_id)),
                            "send tool results back to the model");
                    check(messages.stream().anyMatch(m -> m.tool_calls != null && m.tool_calls.length == 1),
                            "retain the assistant tool-call history");
                    check("none".equals(options.toolChoice), "force final answer with tools still present");
                    check(tools != null && tools.size() == 1, "keep tool definitions in final request");
                    choice.message.content = FINAL;
                    choice.finish_reason = truncate ? "length" : "stop";
                    if (callback != null) {
                        callback.onCompletion("{\"sources\":[{\"url\":\"https://example.com\",\"title\":\"hello");
                        callback.onCompletion(" ");
                        callback.onCompletion("world\",\"content\":\"summary\"}]}");
                    }
                }
                return response;
            }
        };
        ToolLoopOptions loop = new ToolLoopOptions();
        loop.MaxToolRounds = 2;
        StringBuilder callbackText = new StringBuilder();
        var response = context.runTurn(provider, "offline", List.of(tool), request, loop, callbackText::append);
        String expected = format == null ? INTERMEDIATE + "\n\n" + FINAL : FINAL;
        check(response.choices[0].message.content.equals(expected), "only structured output omits intermediate content");
        check(callbackText.toString().equals(expected), "callbacks match the final structured output");
        check(calls.get() == 2 && executed.get() == 1 && response.usage.total_tokens == 10,
                "keep tool execution and accumulated usage");
        if (format != null) {
            if (truncate) {
                try {
                    StructuredOutputs.content(response);
                    throw new AssertionError("truncated output was accepted");
                } catch (IllegalArgumentException expectedFailure) {
                    // 即使字符串可以解析，finish_reason=length 的响应也不能用于业务写入。
                }
            } else {
                check(KnowledgeSources.parse(StructuredOutputs.content(response)).sources().get(0).title().equals("hello world"),
                        "the final ingestion result is parseable");
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
