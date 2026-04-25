package com.nekoyu.universe.openaiadapter;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.MetaField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.ImageUrlPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.TextPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMTool;
import okhttp3.*;
import okio.BufferedSource;
import org.checkerframework.checker.units.qual.A;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 末屿宇宙 LLMProvider 的 OpenAI API 兼容实现
 * 注意：此 Adapter 支持以堵塞式和流式逻辑，但是堵塞式请求并非原生非流式请求，而是通过流式请求完成，因而仅支持可以流式请求的 API
 * Only supports streamed outputting and function calling models
 */
public class OpenAIChannel extends LLMProvider {
    Logger logger = LoggerFactory.getLogger(OpenAIChannel.class);
    OkHttpClient client;
    Gson gson;
    List<LLMFunction> tools;
    String apikey;
    String baseurl;
    String defaultModel;
    String speciallyAdaptation = null; // 特调选项

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
    public CompletionsResponse completions(String model, MessageList messageList, Map<String, LLMFunction> llmFunctions, ExtensionalArgs extensionalArgs, BufferCallback bufferCallback) throws IOException {
        CompletionsResponse responding = new CompletionsResponse(); // fake unstreamed response
        responding.usage.completion_tokens = 0;
        responding.usage.prompt_tokens = 0;
        responding.usage.total_tokens = 0;
        responding.choices = new CompletionsResponse.Choice[]{new CompletionsResponse.Choice() {{
            message.content = "";
        }}};
        CompletionsRequest cr = new CompletionsRequest();
        if ("dashscope".equals(speciallyAdaptation)) { // 对阿里云百炼进行特调
            cr.stream_options.put("include_usage", true);
        }
        if (model != null) cr.model = model;
        else cr.model = defaultModel;
        // Add functions if exists
        if (llmFunctions != null) for (LLMFunction function : llmFunctions.values()) {
            var oaiTool = new LLMTool();
            oaiTool.function = function;
            oaiTool.type = "function";
            cr.tools.add(oaiTool);
        }
        if (cr.tools.isEmpty()) cr.tools = null;
        // Transfer Universe message list to OpenAI message list
        cr.stream = true;
        if (extensionalArgs.enable_thinking) cr.enable_thinking = true;
//        logger.debug(gson.toJson(cr));
        CompletionsResponse completions = completions(messageList, cr, llmFunctions, bufferCallback, extensionalArgs, 5, responding);
        if (completions.usage.total_tokens > 0)
            logger.info("本次请求消耗 tokens: 输入 {}  输出 {}", completions.usage.prompt_tokens, completions.usage.completion_tokens); // 无言了，百炼的 API 默认不返回 usage
        return completions;
    }

    public CompletionsResponse completions(MessageList ml, CompletionsRequest completionsRequest, Map<String, LLMFunction> llmFunctions, BufferCallback bufferCallback, ExtensionalArgs extensionalArgs, int timeout, CompletionsResponse responding) throws IOException {
        completionsRequest.messages.clear();
        for (MCMessage m : ml) {
            ArrayMessage message = new ArrayMessage();
            if (m.getMetainfo("role") instanceof String role) {
                message.role = role;
                if (role.equals("assistant") && m.getMetainfo("Tool_calls") instanceof Tool_call[] toolCalls) message.tool_calls = toolCalls;
                if (role.equals("tool") && m.getMetainfo("tool_call_id") instanceof String tool_call_id) message.tool_call_id = tool_call_id;
            } else message.role = "user";
            for (MsgField mf : m.messageFields) {
                if (mf instanceof ImageField imageField) {
                    message.content.add(new ImageUrlPiece(imageField.getUrl().toString()));
                } else message.content.add(new TextPiece(mf.toString()));
            }
            completionsRequest.messages.add(message);
        }
        if (extensionalArgs.systemPromptFirst != null) {
            ArrayMessage systemPromptFirst = new ArrayMessage();
            systemPromptFirst.role = "system";
            systemPromptFirst.content.add(new TextPiece(extensionalArgs.systemPromptFirst));
            completionsRequest.messages.add(0, systemPromptFirst);
        }
        if (extensionalArgs.systemPromptLast != null) {
            ArrayMessage systemPromptLast = new ArrayMessage();
            systemPromptLast.role = "system";
            systemPromptLast.content.add(new TextPiece(extensionalArgs.systemPromptLast));
            completionsRequest.messages.add(systemPromptLast);
        }
        logger.debug(gson.toJson(completionsRequest));
        boolean outputted = false;
        if (timeout <= 1) { // 超时时，禁用所有tool，进行最后一次请求，避免死循环
            completionsRequest.tools = null;
            ArrayMessage am = new ArrayMessage();
            am.content.add(new TextPiece("[WARNING] 工具调用回合超时，工具被禁用"));
            am.role = "system";
            completionsRequest.messages.add(am);
        }
        Request req = new Request.Builder()
                .url(baseurl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apikey)
                .post(RequestBody.create(gson.toJson(completionsRequest), MediaType.get("application/json; charset=utf-8")))
                .build();
        try (Response response = client.newCall(req).execute()) {
            if (response.isSuccessful()) {
                BufferedSource source = response.body().source();
                String line;
                Map<Integer, Tool_call> tool_calls = new HashMap<>();
                String finish_reason = "unfinished";
                StringBuilder content = new StringBuilder();
                while ((line = source.readUtf8Line()) != null) {
                    if (line.startsWith("data: ")) {
//                        logger.debug(line);
                        String json = line.substring(6).trim();
                        if (json.startsWith("{")) {
                            DataLine dl = gson.fromJson(json, DataLine.class);
                            if (dl.choices != null && dl.choices.length > 0) {
                                DataLine.Choice choice = dl.choices[0];
                                if (choice.delta.content != null && !choice.delta.content.isBlank()) {
                                    bufferCallback.onCompletion(choice.delta.content);
                                    outputted = true;
                                    content.append(choice.delta.content);
                                    responding.choices[0].message.content += choice.delta.content;
                                }
                                if (choice.delta.tool_calls != null) {
                                    for (Tool_call tool_call : choice.delta.tool_calls) {
                                        Tool_call loading = tool_calls.get(tool_call.index);
                                        if (loading == null) {
                                            loading = new Tool_call();
                                            tool_calls.put(tool_call.index, loading);
                                            loading.function.arguments = "";
                                        }
                                        if (tool_call.id != null && !tool_call.id.isBlank()) loading.id = tool_call.id;
                                        if (tool_call.type != null && !tool_call.type.isBlank())
                                            loading.type = tool_call.type;
                                        if (tool_call.function.name != null && !tool_call.function.name.isBlank())
                                            loading.function.name = tool_call.function.name;
                                        if (tool_call.function.arguments != null)
                                            loading.function.arguments += tool_call.function.arguments;
                                    }
                                }
                                if (choice.finish_reason != null) {
                                    finish_reason = choice.finish_reason;
                                    responding.choices[0].finish_reason = finish_reason;
                                }
                            }
                            if (dl.usage != null) {
                                responding.usage.total_tokens += dl.usage.total_tokens;
                                responding.usage.prompt_tokens += dl.usage.prompt_tokens;
                                responding.usage.completion_tokens += dl.usage.completion_tokens;
                            }
                        }
                    }
                }
                // 响应体接收完毕
                MCMessage assistantMcm = new MCMessage();
                ml.add(assistantMcm);
                assistantMcm.putMetainfo("role", "assistant");
                assistantMcm.messageFields.add(new TextField(content.toString()));
                switch (finish_reason) {
                    case "stop" -> {
                        return responding;
                    }
                    case "tool_calls" -> {
                        if (outputted) {
                            responding.choices[0].message.content += "\n\n";
                            bufferCallback.onCompletion("\n\n");
                        }
                        Tool_call[] toolCalls = tool_calls.values().toArray(new Tool_call[0]);
                        assistantMcm.putMetainfo("Tool_calls", toolCalls);
                        boolean next = false;
                        for (Tool_call tool_call : toolCalls) {
                            if (tool_call.function.arguments.startsWith("\""))
                                tool_call.function.arguments = tool_call.function.arguments.substring(1, tool_call.function.arguments.length() - 1); // 不知道为什么DeepSeek喜欢在arg前后各加一个"，删了
                            logger.debug(gson.toJson(tool_call));
                            LLMFunction llmFunction = llmFunctions.get(tool_call.function.name);
                            MCMessage toolMcm = new MCMessage();
                            toolMcm.putMetainfo("role", "tool");
                            toolMcm.putMetainfo("tool_call_id", tool_call.id);
                            Map args = null;
                            try {
                                args = gson.fromJson(tool_call.function.arguments, HashMap.class);
                            } catch (JsonSyntaxException e) {
                                tool_call.function.arguments.replaceAll("\\\\", ""); // 再捞一下 LLM 的零分试卷
                                try {
                                    args = gson.fromJson(tool_call.function.arguments, HashMap.class);
                                } catch (JsonSyntaxException ex) {
                                    logger.warn("Assistant 唐完了，输出的参数 Gson 无法解析 {}", tool_call.function.arguments);
                                    toolMcm.messageFields.add(new TextField(ex.getMessage()));
                                }
                            }
                            if (args != null && extensionalArgs != null) args.putAll(extensionalArgs.placeholders);
                            String ctt;
                            if (args == null)
                                ctt = "未知原因的工具调用错误";
                            else try {
                                ctt = llmFunction.callback.callback(args);
                            } catch (Exception e) {
                                ctt = "调用工具失败: " + e.getMessage();
                            }
                            if (ctt != null) {
                                next = true;
                                toolMcm.messageFields.add(new TextField(ctt));
                            }
                            ml.add(toolMcm);
                            logger.info("Assistant 调用了 {}，参数 {}", tool_call.function.name, tool_call.function.arguments);
                        }
                        if (next)
                            return completions(ml, completionsRequest, llmFunctions, bufferCallback, extensionalArgs, timeout - 1, responding);
                    }
                    case "unfinished" -> logger.error("出现意外导致请求未完成\nRaw req: {}", gson.toJson(completionsRequest));
                }
            } else {
                logger.error("Error Req Body {}", gson.toJson(completionsRequest));
                logger.error("Error Resp Body {}", response.body().string());
                throw new IOException("Unexpected code " + response); // 没成功就是抽风了，至于是服务器抽风，还是账号抽风，还是网络抽风，不想管
            }
        }
        throw new IOException("Unknown error");
    }

    public void setBaseurl(String baseurl) {
        this.baseurl = baseurl;
        Pattern pattern = Pattern.compile("^https://[^/]+\\.aliyuncs\\.com(/.*)?$");
        Matcher matcher = pattern.matcher(baseurl);
        if (matcher.find()) {
            speciallyAdaptation = "dashscope";
        }
    }
}
