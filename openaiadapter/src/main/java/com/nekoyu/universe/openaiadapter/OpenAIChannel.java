package com.nekoyu.universe.openaiadapter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.Providers.LLMProvider.Embedding;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import com.nekoyu.universe.openaiadapter.RequestBodies.AliyunBailianReq;
import com.nekoyu.universe.openaiadapter.RequestBodies.OpenAICompletionsRequest;
import com.nekoyu.universe.openaiadapter.RequestBodies.OpenAIReq;
import okhttp3.*;
import okio.BufferedSource;
import org.slf4j.Logger;

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
    public OpenAIChannel() {
        client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        gson = new Gson();
    }

    /**
     * 请求一次模型并返回单轮 Model Response。
     * <p>
     * 本实现只负责：请求体序列化 -> 调用 OpenAI 兼容 API -> SSE 解析 -> 返回当轮结果。
     * Tool/Function Calling 循环由上层 Context/Agent 状态机驱动，这里不做任何 Context 写入与工具执行。
     */
    @Override
    public CompletionsResponse completions(String model, List<Message> messages, List<LLMFunction> llmFunctions, CompletionsRequest completionsRequest, BufferCallback bufferCallback) throws IOException {
        OpenAICompletionsRequest cr = buildRequest(model, llmFunctions, completionsRequest);
        cr.messages = messages;
        cr.stream = true;
        logger.debug(gson.toJson(cr));
        Request req = new Request.Builder()
                .url(baseurl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apikey)
                .post(RequestBody.create(toBody(cr), MediaType.get("application/json; charset=utf-8")))
                .build();
        try (Response response = client.newCall(req).execute()) {
            if (response.isSuccessful()) {
                CompletionsResponse responding = new CompletionsResponse();
                responding.usage.completion_tokens = 0;
                responding.usage.prompt_tokens = 0;
                responding.usage.total_tokens = 0;
                responding.choices = new CompletionsResponse.Choice[]{new CompletionsResponse.Choice() {{
                    message.content = "";
                    message.reasoning_content = "";
                }}};
                BufferedSource source = response.body().source();
                String line;
                // 以 index 收纳流式 tool_call 片段，输出时按 index 升序还原，保证多工具调用顺序稳定
                Map<Integer, Tool_call> tool_calls = new LinkedHashMap<>();
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
                Tool_call[] toolCalls = tool_calls.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(Map.Entry::getValue)
                        .toArray(Tool_call[]::new);
                responding.choices[0].message.tool_calls = toolCalls;
                responding.choices[0].message.content = content.toString();
                responding.choices[0].message.reasoning_content = reasoning_content.toString();
                responding.choices[0].finish_reason = finish_reason;
                if ("unfinished".equals(finish_reason))
                    logger.error("出现意外导致请求未完成\nRaw req: {}", gson.toJson(cr));
                if (responding.usage.total_tokens > 0)
                    logger.info("Completions-Usage: ({}) 输入 {} Tokens  输出 {} Tokens", cr.model, responding.usage.prompt_tokens, responding.usage.completion_tokens); // 无言了，百炼的 API 默认不返回 usage
                return responding;
            } else {
                logger.error("Error Req Body {}", gson.toJson(cr));
                logger.error("Error Resp Body {}", response.body().string());
                throw new IOException("Unexpected code " + response); // 没成功就是抽风了，至于是服务器抽风，还是账号抽风，还是网络抽风，不想管
            }
        }
    }

    /** 构建 OpenAI 兼容请求体（含 dashscope / gpt 特调），并把中性工具列表装入请求 */
    private OpenAICompletionsRequest buildRequest(String model, List<LLMFunction> llmFunctions, CompletionsRequest completionsRequest) {
        OpenAICompletionsRequest cr;
        switch (speciallyAdaptation) {
            case "dashscope" -> {
                AliyunBailianReq bailian = new AliyunBailianReq();
                bailian.stream_options.put("include_usage", true);
                if (completionsRequest.enable_thinking) bailian.enable_thinking = true;
                cr = bailian;
            }
            case "gpt" -> {
                OpenAIReq openai = new OpenAIReq();
                if (completionsRequest.enable_thinking) openai.reasoning.effort = OpenAIReq.Reasoning.Effort.low;
                cr = openai;
            }
            default -> cr = new OpenAICompletionsRequest();
        }
        if (model != null) cr.model = model;
        else cr.model = defaultModel;
        if (llmFunctions != null) cr.tools.addAll(llmFunctions);
        if (cr.tools.isEmpty()) cr.tools = null;
        // 仅在确实携带 tools 时下发 tool_choice，避免无 tools 时设置 tool_choice 触发部分 Provider 报错
        if (cr.tools != null && completionsRequest != null && completionsRequest.toolChoice != null)
            cr.tool_choice = completionsRequest.toolChoice;
        return cr;
    }

    /** 序列化请求体，并把中性层的 LLMFunction 列表装进 OpenAI 的 {"type":"function","function":{...}} 外壳 */
    private String toBody(OpenAICompletionsRequest request) {
        JsonObject body = gson.toJsonTree(request).getAsJsonObject();
        if (body.has("tools") && !body.get("tools").isJsonNull() && !body.getAsJsonArray("tools").isEmpty()) {
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
