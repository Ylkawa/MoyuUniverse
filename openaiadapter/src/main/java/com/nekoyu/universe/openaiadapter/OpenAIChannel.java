package com.nekoyu.universe.openaiadapter;

import com.google.gson.Gson;
import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.Providers.LLMProvider.Assistant;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ArrayMessage;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.TextPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Tool_call;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.LLMTool;
import okhttp3.*;
import okio.BufferedSource;
import org.checkerframework.checker.units.qual.A;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 末屿宇宙LLMProvider的OpenAI API兼容实现
 * 注意：此Adapter支持以堵塞式和流式逻辑，但是堵塞式请求并非原生请求，而是通过流式请求完成，因而仅支持可以流式请求的模型
 * Only supports streamed outputting and function calling models
 */
public class OpenAIChannel extends LLMProvider {
    static Logger logger = LoggerFactory.getLogger(OpenAIChannel.class);
    OkHttpClient client;
    Gson gson;
    List<LLMFunction> tools;
    String apikey;
    String baseurl;

    public OpenAIChannel() {
        client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        gson = new Gson();
        tools = new ArrayList<>();
    }

    @Override
    public CompletionsResponse completions(String model, MessageList messageList, Map<String, LLMFunction> llmFunctions, BufferCallback bufferCallback) throws IOException {
        CompletionsResponse responding = new CompletionsResponse(); // fake unstreamed response
        responding.usage.completion_tokens = 0;
        responding.usage.prompt_tokens = 0;
        responding.usage.total_tokens = 0;
        responding.choices = new CompletionsResponse.Choice[]{new CompletionsResponse.Choice(){{message.content = "";}}};
        CompletionsRequest cr = new CompletionsRequest();
        // Add functions if exists
        if (llmFunctions != null) for (LLMFunction function : llmFunctions.values()) {
            var oaiTool = new LLMTool();
            oaiTool.function = function;
            oaiTool.type = "function";
            cr.tools.add(oaiTool);
        }
        // Transfer Universe message list to OpenAI message list
        for (MCMessage m : messageList) {
            ArrayMessage message = new ArrayMessage();
            if (m.universe) {
                message.role = "assistant";
            } else if (m.getMetainfo("role") instanceof String role) {
                message.role = role;
            } else message.role = "user";
            message.content.add(new TextPiece(m.solveAll()));
            cr.messages.add(message);
        }
        cr.stream = true;
        return completions(cr, null, bufferCallback, 5, responding);
    }

    public CompletionsResponse completions(CompletionsRequest completionsRequest, Map<String, LLMFunction> llmTools, BufferCallback bufferCallback, int timeout, CompletionsResponse responding) throws IOException {
        if (timeout <= 1) { // 超时时，禁用所有tool，进行最后一次请求，避免死循环
            completionsRequest.tools = new ArrayList<>();
        }
        Request req = new Request.Builder()
                .url(baseurl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apikey)
                .post(RequestBody.create(gson.toJson(completionsRequest), MediaType.get("application/json; charset=utf-8")))
                .build();
        // 这里得改成异步的，不然不够先进
        try (Response response = client.newCall(req).execute()) {
            if (response.isSuccessful()) {
                BufferedSource source = response.body().source();
                String line;
                Map<Integer, Tool_call> tool_calls = new HashMap<>();
                String finish_reason = "unfinished";
                Tool_call loading;
                while ((line = source.readUtf8Line()) != null) {
                    if (line.startsWith("data: ")) {
                        DataLine dl = gson.fromJson(line.split(" ")[1], DataLine.class);
                        DataLine.Choice choice = dl.choices[0];
                        if (choice.delta.content != null && choice.delta.content.isBlank()) {
                            bufferCallback.onCompletion(choice.delta.content);
                            responding.choices[0].message.content += choice.delta.content;
                        }
                        if (choice.delta.tool_calls != null) {
                            for (Tool_call tool_call : choice.delta.tool_calls) {
                                loading = tool_calls.get(tool_call.index);
                                if (loading == null) {
                                    loading = new Tool_call();
                                    tool_calls.put(tool_call.index, loading);
                                    loading.function.arguments = "";
                                }
                                if (tool_call.id != null) loading.id = tool_call.id;
                                if (tool_call.type != null) loading.type = tool_call.type;
                                if (tool_call.function.name != null) loading.function.name = tool_call.function.name;
                                loading.function.arguments += tool_call.function.arguments;
                            }
                        }
                        if (choice.finish_reason != null) {
                            finish_reason = choice.finish_reason;
                            responding.choices[0].finish_reason = finish_reason;
                        }
                        if (dl.usage != null) {
                            responding.usage.total_tokens += dl.usage.total_tokens;
                            responding.usage.prompt_tokens += dl.usage.prompt_tokens;
                            responding.usage.completion_tokens += dl.usage.completion_tokens;
                        }
                    }
                }
                // 响应体接收完毕
                switch (finish_reason) {
                    case "stop" -> {
                        return responding;
                    }
                    case "tool_calls" -> {
                        responding.choices[0].message.content += "\n\n\n";
                        Message msg = new Message();
                        completionsRequest.messages.add(msg);
                        msg.tool_calls = tool_calls.values().toArray(new Tool_call[0]);
                        for (Tool_call tool_call : msg.tool_calls) {
                            LLMFunction tool = llmTools.get(tool_call.function.name);
                            ArrayMessage toolMsg = new ArrayMessage();
                            toolMsg.role = "tool";
                            toolMsg.tool_call_id = tool_call.id;
                            toolMsg.content.add(new TextPiece(tool.callback.callback(gson.fromJson(tool_call.function.arguments, HashMap.class))));
                        }
                        return completions(completionsRequest, llmTools, bufferCallback, timeout-1, responding);
                    }
                    case "unfinished" -> throw new IOException("出现意外导致请求未完成");
                }
            } else throw new IOException("Unexpected code " + response); // 没成功就是抽风了，至于是服务器抽风，还是账号抽风，还是网络抽风，不想管
        }
        throw new IOException("Unknown error");
    }
}
