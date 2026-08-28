package com.nekoyu.universe.openaiadapter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.Providers.LLMProvider.Embedding;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.ImageUrlPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.TextPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import com.nekoyu.universe.openaiadapter.RequestBodies.AliyunBailianReq;
import com.nekoyu.universe.openaiadapter.RequestBodies.OpenAIReq;
import okhttp3.*;
import okio.BufferedSource;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 末屿宇宙 LLMProvider 的 OpenAI API 兼容实现
 * 注意：此 Adapter 支持以堵塞式和流式逻辑，但是堵塞式请求并非原生非流式请求，而是通过流式请求完成，因而仅支持可以流式请求的 API
 * Only supports streamed outputting and function calling models
 */
public class OpenAIChannel extends LLMProvider implements Embedding {
    Logger logger = null;
    OkHttpClient client;
    Gson gson;
    String apikey;
    String baseurl;
    String defaultModel;
    String defaultEmbeddingModel;
    String speciallyAdaptation = null; // 特调选项
    /** 本通道的可调参数，整体通过 {@link #setCompletionsOptions(CompletionsOptions)} 注入 */
    CompletionsOptions options = new CompletionsOptions();

    public OpenAIChannel() {
        client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        gson = new Gson();
    }

    public void setCompletionsOptions(CompletionsOptions options) {
        if (options != null) this.options = options;
    }

    @Override
    public CompletionsResponse completions(String model, MessageList messageList, Map<String, LLMFunction> llmFunctions, ExtensionalArgs extensionalArgs, BufferCallback bufferCallback) throws IOException {
        CompletionsResponse responding = new CompletionsResponse(); // fake unstreamed response
        responding.usage.completion_tokens = 0;
        responding.usage.prompt_tokens = 0;
        responding.usage.total_tokens = 0;
        responding.choices = new CompletionsResponse.Choice[]{new CompletionsResponse.Choice() {{
            message.content = "";
            message.reasoning_content = "";
        }}};
        CompletionsRequest cr;
        switch (speciallyAdaptation) {
            case "dashscope" -> {
                AliyunBailianReq bailian = new AliyunBailianReq();
                bailian.stream_options.put("include_usage", true);
                if (extensionalArgs.enable_thinking) bailian.enable_thinking = true;
                cr = bailian;
            }
            case "gpt" -> {
                OpenAIReq openai = new OpenAIReq();
                if (extensionalArgs.enable_thinking) openai.reasoning.effort = OpenAIReq.Reasoning.Effort.low;
                cr = openai;
            }
            default -> cr = new CompletionsRequest();
        }
        if (model != null) cr.model = model;
        else cr.model = defaultModel;
        // Add functions if exists
        if (llmFunctions != null) cr.tools.addAll(llmFunctions.values());
        if (cr.tools.isEmpty()) cr.tools = null;
        // Transfer Universe message list to OpenAI message list
        cr.stream = true;
        logger.debug(gson.toJson(cr));
        CompletionsResponse completions = completions(messageList, cr, llmFunctions, bufferCallback, extensionalArgs, options.maxToolRounds, new ToolLoopControl(options), responding);
        if (completions.usage.total_tokens > 0)
            logger.info("Completions-Usage: ({}) 输入 {} Tokens  输出 {} Tokens", cr.model, completions.usage.prompt_tokens, completions.usage.completion_tokens); // 无言了，百炼的 API 默认不返回 usage
        return completions;
    }

    /** 序列化请求体，并把中性层的 LLMFunction 列表装进 OpenAI 的 {"type":"function","function":{...}} 外壳 */
    private String toBody(CompletionsRequest request) {
        JsonObject body = gson.toJsonTree(request).getAsJsonObject();
        if (body.has("tools") && !body.get("tools").isJsonNull() && body.getAsJsonArray("tools").size() > 0) {
            JsonArray wrapped = new JsonArray();
            for (JsonElement tool : body.getAsJsonArray("tools")) {
                JsonObject envelope = new JsonObject();
                envelope.addProperty("type", "function");
                envelope.add("function", tool);
                wrapped.add(envelope);
            }
            body.add("tools", wrapped);
        }
        return body.toString();
    }

    public CompletionsResponse completions(MessageList ml, CompletionsRequest completionsRequest, Map<String, LLMFunction> llmFunctions, BufferCallback bufferCallback, ExtensionalArgs extensionalArgs, int timeout, ToolLoopControl state, CompletionsResponse responding) throws IOException {
        completionsRequest.messages.clear(); // 每一轮都重新构建了消息列表
        for (MCMessage m : ml) {
            ArrayMessage message = new ArrayMessage();
            if (m.sender.equals(extensionalArgs.assistant)) message.role = "assistant";
            else if (m.getMetainfo("role") instanceof String role) {
                message.role = role;
                if (role.equals("assistant") && m.getMetainfo("Tool_calls") instanceof Tool_call[] toolCalls) {
                    if (m.getMetainfo("reasoning") instanceof String s) message.reasoning_content = s; // 回传思考链
                    message.tool_calls = toolCalls;
                }
                if (role.equals("tool") && m.getMetainfo("tool_call_id") instanceof String tool_call_id)
                    message.tool_call_id = tool_call_id;
            } else message.role = "user";
            for (MsgField mf : m.messageFields) {
                if (!(Objects.equals(message.role, "assistant") && Objects.equals(speciallyAdaptation, "dashscope")) && mf instanceof ImageField imageField) {
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
        if (timeout <= 0) { // 兜底：无论如何都要终结循环
            logger.error("{} 工具调用循环未能正常中止，强制返回当前输出", completionsRequest.model);
            return responding;
        }
        if (timeout <= 1 || state.calls >= state.maxCalls) { // 回合超时或本轮工具调用已达上限时，禁用所有tool，进行最后一次请求，避免死循环
            if (state.calls >= state.maxCalls)
                logger.warn("{} 单次回复内工具调用次数已达上限({})，工具被禁用，强制模型文字回复", completionsRequest.model, state.maxCalls);
            completionsRequest.tools = null;
            ArrayMessage am = new ArrayMessage();
            am.content.add(new TextPiece("[WARNING] 工具调用回合超时或工具调用已达次数上限，工具已被禁用，请立即停止调用工具，直接基于已有的信息作答"));
            am.role = "system";
            completionsRequest.messages.add(am);
        }
        Request req = new Request.Builder()
                .url(baseurl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apikey)
                .post(RequestBody.create(toBody(completionsRequest), MediaType.get("application/json; charset=utf-8")))
                .build();
        try (Response response = client.newCall(req).execute()) {
            if (response.isSuccessful()) {
                BufferedSource source = response.body().source();
                String line;
                Map<Integer, Tool_call> tool_calls = new HashMap<>();
                String finish_reason = "unfinished";
                StringBuilder content = new StringBuilder();
                StringBuilder reasoning_content = new StringBuilder();
                while ((line = source.readUtf8Line()) != null) {
                    if (line.startsWith("data: ")) {
//                        logger.debug(line);
                        String json = line.substring(6).trim();
                        if (json.startsWith("{")) {
                            DataLine dl = gson.fromJson(json, DataLine.class);
                            if (dl.choices != null && dl.choices.length > 0) {
                                DataLine.Choice choice = dl.choices[0];
                                if (choice.delta.content != null && !choice.delta.content.isBlank()) {
                                    if (bufferCallback != null) bufferCallback.onCompletion(choice.delta.content);
                                    outputted = true;
                                    content.append(choice.delta.content);
                                    responding.choices[0].message.content += choice.delta.content;
                                }
                                if (choice.delta.reasoning_content != null && !choice.delta.reasoning_content.isBlank()) {
                                    reasoning_content.append(choice.delta.reasoning_content);
                                    responding.choices[0].message.reasoning_content += choice.delta.reasoning_content;
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
                            if (bufferCallback != null) bufferCallback.onCompletion("\n\n");
                        }
                        if (responding.choices[0].message.reasoning_content != null)
                            assistantMcm.putMetainfo("reasoning", responding.choices[0].message.reasoning_content); // 存放思考链
                        Tool_call[] toolCalls = tool_calls.values().toArray(new Tool_call[0]);
                        assistantMcm.putMetainfo("Tool_calls", toolCalls);
                        boolean next = false;
                        for (Tool_call tool_call : toolCalls) {
                            if (tool_call.function.arguments.startsWith("\""))
                                tool_call.function.arguments = tool_call.function.arguments.substring(1, tool_call.function.arguments.length() - 1); // 不知道为什么DeepSeek喜欢在arg前后各加一个"，删了
                            logger.debug(gson.toJson(tool_call));
                            MCMessage toolMcm = new MCMessage();
                            toolMcm.putMetainfo("role", "tool");
                            toolMcm.putMetainfo("tool_call_id", tool_call.id);
                            if (llmFunctions == null) {
                                logger.error("LLMFunctions is null and LLM is trying to call a undefined function {}", tool_call.function.name);
                                toolMcm.messageFields.add(new TextField("None function exist, stop calling functions."));
                                return completions(ml, completionsRequest, llmFunctions, bufferCallback, extensionalArgs, timeout - 1, state, responding);
                            }
                            String toolName = tool_call.function.name;
                            LLMFunction llmFunction = llmFunctions.get(toolName);
                            if (llmFunction == null) {
                                logger.error("LLM is trying to call a undefined function {}", toolName);
                                toolMcm.messageFields.add(new TextField("You're trying to call a undefined function " + toolName + "."));
                                return completions(ml, completionsRequest, llmFunctions, bufferCallback, extensionalArgs, timeout - 1, state, responding);
                            }

                            // 防死循环：单次回复内工具调用次数已达上限则不再执行，仅告知模型停止调用
                            if (state.calls >= state.maxCalls) {
                                logger.warn("{} 单次回复内工具调用次数已达到上限({})，跳过执行工具 {}", completionsRequest.model, state.maxCalls, toolName);
                                toolMcm.messageFields.add(new TextField(
                                        "你本次回复已经达到工具调用次数上限(" + state.maxCalls + ")，该调用已被忽略。" +
                                                "请立即停止调用任何工具，直接基于你已有的信息回答用户。如果信息不足，请如实说明。"));
                                ml.add(toolMcm);
                                next = true;
                                continue;
                            }

                            // 防死循环：完全相同的参数不再重复执行，直接返回上次的结果
                            String callKey = toolName + "|" + normalizeArgs(tool_call.function.arguments);
                            if (state.exactResults.containsKey(callKey)) {
                                logger.warn("{} 重复调用 {}，参数 {}", completionsRequest.model, toolName, tool_call.function.arguments);
                                toolMcm.messageFields.add(new TextField(
                                        "你已经用完全相同的参数调用过工具 " + toolName + "，请不要重复调用！直接基于已有信息回答。\n上次结果：\n" +
                                                state.exactResults.get(callKey)));
                                ml.add(toolMcm);
                                next = true;
                                continue;
                            }

                            JsonElement args = null;
                            try {
                                args = JsonParser.parseString(tool_call.function.arguments);
                            } catch (JsonSyntaxException e) {
                                tool_call.function.arguments.replaceAll("\\\\", ""); // 再捞一下 LLM 的零分试卷
                                try {
                                    args = JsonParser.parseString(tool_call.function.arguments);
                                } catch (JsonSyntaxException ex) {
                                    logger.warn("Assistant 唐完了，输出的参数 Gson 无法解析 {}", tool_call.function.arguments);
                                    toolMcm.messageFields.add(new TextField(ex.getMessage()));
                                }
                            }
                            if (args != null && args.isJsonObject())
                                extensionalArgs.placeholders.forEach(args.getAsJsonObject()::addProperty);
                            MFChain ctt;
                            if (args == null) {
                                ctt = new MFChain();
                                ctt.add(new TextField("未知原因的工具调用错误"));
                            } else try {
                                ctt = llmFunction.callback.call(args);
                            } catch (Exception e) {
                                ctt = new MFChain();
                                ctt.add(new TextField("调用工具失败: " + e.getMessage()));
                            }
                            if (ctt != null) {
                                state.calls++;
                                state.exactResults.put(callKey, ctt.toString());
                                next = true;
                                toolMcm.messageFields = ctt;
                            }
                            ml.add(toolMcm);
                            logger.info("{} 调用了 {}，参数 {}", completionsRequest.model, toolName, tool_call.function.arguments);
                        }
                        if (next)
                            return completions(ml, completionsRequest, llmFunctions, bufferCallback, extensionalArgs, timeout - 1, state, responding);
                    }
                    case "unfinished" ->
                            logger.error("出现意外导致请求未完成\nRaw req: {}", gson.toJson(completionsRequest));
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
            logger.debug("识别到百炼API，启用百炼特殊适配");
            return;
        }
        if (defaultModel != null && defaultModel.startsWith("gpt-5")) {
            speciallyAdaptation = "gpt";
            logger.debug("识别到GPT模型，启用GPT特殊适配");
            return;
        }
        speciallyAdaptation = "none";
    }

    /** 单次回复内工具调用的状态锁存，用于防止模型陷入工具调用死循环 */
    private static class ToolLoopControl {
        final int maxCalls;
        int calls;
        final Map<String, String> exactResults = new HashMap<>();

        ToolLoopControl(CompletionsOptions options) {
            this.maxCalls = Math.max(1, options.maxToolCallsPerTurn);
        }
    }

    private static String normalizeArgs(String args) {
        if (args == null) return "";
        return args.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    @Override
    public EmbeddingResponse embedding(EmbeddingRequest embeddingRequest) {
        int batchesNum = (int) Math.ceil((double) embeddingRequest.message.size() / 10); // 分几次请求完成
        EmbeddingResponse finalResponse = new EmbeddingResponse();
        String model;
        if (embeddingRequest.model != null && !embeddingRequest.model.isEmpty())
            model = embeddingRequest.model;
        else model = defaultEmbeddingModel;
        Map<Integer, EmbeddingResponse.Embedding> results = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(batchesNum);
        for (int i = 0; i <= batchesNum - 1; i++) {
            int finalI = i;
            executor.submit(() -> {
                int startAt = finalI * 10; // 从第几个开始取
                int endAt = Math.min(finalI * 10 + 10, embeddingRequest.message.size()); // 从第几个结束
                // 翻译
                OAIEmbeddingRequest request = new OAIEmbeddingRequest();
                List<MFChain> subList = embeddingRequest.message.subList(startAt, endAt);
                request.model = model;
                if (request.model == null) throw new RuntimeException("Model not defined");
                for (var msg : subList) {
                    request.input.add(msg.toString());
                }
                Request req = new Request.Builder()
                        .url(baseurl + "/embeddings")
                        .addHeader("Authorization", "Bearer " + apikey)
                        .post(RequestBody.create(gson.toJson(request), MediaType.get("application/json; charset=utf-8")))
                        .build();
                String string = null;
                try (Response resp = client.newCall(req).execute()) {
                    string = resp.body().string();
                    if (!resp.isSuccessful()) throw new IOException("Unexpected code " + string);
                    EmbeddingResponse embeddingResponse = gson.fromJson(string, EmbeddingResponse.class);
                    for (var data : embeddingResponse.data) {
                        data.index += startAt;
                        results.put(data.index, data);
                    }
                    if (finalResponse.object == null) finalResponse.object = embeddingResponse.object;
                    if (finalResponse.id == null) finalResponse.id = embeddingResponse.id;
                    if (embeddingResponse.usage != null)
                        finalResponse.usage.prompt_tokens += embeddingResponse.usage.prompt_tokens; // 无言了，百炼的 API 默认不返回 usage
                } catch (IOException e) {
                    logger.error("operation failed with resp body: {}", string, e);
                }
            });
        }
        executor.shutdown();
        try {
            while (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("Still awaiting responses");
            }
            for (int i = 0; i < embeddingRequest.message.size(); i++) {
                finalResponse.data.add(results.get(i));
            }
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
        if (finalResponse.usage.prompt_tokens != 0) logger.info("Embedding-Usage: ({}) {} Tokens", model, finalResponse.usage.prompt_tokens);
        return finalResponse;
    }

    public void setApiKey(String apikey) {
        this.apikey = apikey;
    }
}
