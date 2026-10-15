package com.nekoyu.universe.openaiadapter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.JsonSchema;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ResponseFormat;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.ImageUrlPiece;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 可直接运行；拦截器提供 SSE 响应，无需调用模型 API。 */
public class ResponseFormatTest {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        JsonSchema oldSchema = JsonSchema.object().property("query", JsonSchema.string()).required("query");
        check(GSON.toJsonTree(oldSchema).equals(JsonParser.parseString("""
                {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
                """)), "existing tool schema serialization is unchanged");
        check(GSON.toJsonTree(JsonSchema.object().additionalProperties(true))
                .getAsJsonObject().get("additionalProperties").getAsBoolean(), "explicit true is preserved");

        JsonSchema schema = JsonSchema.object()
                .property("answer", JsonSchema.string())
                .property("tags", JsonSchema.array(JsonSchema.enumType("a", "b")))
                .property("details", JsonSchema.object().property("count", JsonSchema.integer())
                        .required("count").additionalProperties(false))
                .required("answer", "tags", "details").additionalProperties(false);
        ResponseFormat structured = ResponseFormat.jsonSchema("result", schema, true);
        JsonObject expectedSchemaFormat = JsonParser.parseString("""
                {"type":"json_schema","json_schema":{"name":"result","strict":true,"schema":{
                  "type":"object","properties":{
                    "answer":{"type":"string"},
                    "tags":{"type":"array","items":{"type":"string","enum":["a","b"]}},
                    "details":{"type":"object","properties":{"count":{"type":"integer"}},
                               "required":["count"],"additionalProperties":false}},
                  "required":["answer","tags","details"],"additionalProperties":false}}}
                """).getAsJsonObject();
        check(!GSON.toJsonTree(ResponseFormat.jsonSchema("loose", schema, false))
                .getAsJsonObject().getAsJsonObject("json_schema").get("strict").getAsBoolean(),
                "explicit strict false is preserved");

        List<LLMFunction> tools = List.of(LLMFunction.builder().name("lookup").parameters(oldSchema).build());
        // 同时包含 JSON 字符串内部的空格和独立的格式化空白字符。
        String[] pieces = {"", "{\"answer\":\"hello", " ", "world\",", "\n", "\t",
                "\"tags\":[\"a\"],\"details\":{\"count\":1}}"};
        for (String adaptation : List.of("none", "dashscope", "gpt")) {
            JsonObject plain = invoke(adaptation, null, null, pieces);
            check(!plain.has("response_format"), "default request omits response_format: " + adaptation);
            check(!plain.has("tools") && !plain.has("tool_choice"), "default request omits tools");

            JsonObject jsonMode = invoke(adaptation, ResponseFormat.jsonObject(), null, pieces);
            check(jsonMode.get("response_format").equals(JsonParser.parseString("{\"type\":\"json_object\"}")),
                    "JSON mode uses the protocol object: " + adaptation);

            JsonObject structuredBody = invoke(adaptation, structured, tools, pieces);
            check(structuredBody.get("response_format").equals(expectedSchemaFormat),
                    "schema format survives request construction: " + adaptation);
            JsonObject tool = structuredBody.getAsJsonArray("tools").get(0).getAsJsonObject();
            check(tool.get("type").getAsString().equals("function"), "tool envelope remains intact");
            check(tool.getAsJsonObject("function").get("parameters").equals(GSON.toJsonTree(oldSchema)),
                    "output schema does not change tool parameters");
            check(structuredBody.get("tool_choice").getAsString().equals("none"), "tool_choice is preserved");
            check(structured.jsonSchema.schema == schema, "reuse the supplied JsonSchema instance");
        }
        // 传输层必须原样返回无效或被截断的 JSON，由调用方处理。
        invoke("none", structured, null, new String[]{"{\"answer\":", " "});
        checkOutputPolicies();
        System.out.println("ResponseFormatTest passed");
    }

    private static void checkOutputPolicies() throws Exception {
        JsonSchema schema = JsonSchema.object().property("value",
                JsonSchema.anyOf(JsonSchema.string().description("保留完整字段描述"), JsonSchema.of("null")))
                .additionalProperties(true);
        ResponseFormat format = ResponseFormat.jsonSchema("nullable_result", schema, true);
        String[] pieces = {"{\"value\":null}"};
        Message system = new Message("业务规则");
        system.role = "system";
        Message user = new Message("提取资料");
        user.role = "user";
        List<Message> original = List.of(system, user);
        for (String adaptation : List.of("none", "dashscope", "gpt")) {
            for (String model : List.of("qwen3.7-plus", "qwen3.7-flash", "qwen3.7-max",
                    "qwen3.8-flash", "qwen3.10-max", "qwen3.7.1-plus", "qwen4-plus", "qwen42.7-max",
                    "qwen99999999999999999999-flash", "qwen3.8-max-latest", "qwen3.8-max-preview",
                    "qwen3.8-max-2026-09-02", "qwen3.8-max-0902", "qwen4-plus-preview-2026-10-15")) {
                JsonObject body = invoke(adaptation, model, null, format, null, pieces, original);
                check(body.get("response_format").equals(GSON.toJsonTree(format)), "Qwen schema whitelist: " + model);
                checkSchemaPrompt(body, schema, original.size());
            }
            for (String model : List.of("qwen3.6-plus", "qwen3.6.99-max", "qwen3-max", "qwen-plus",
                    "qwen2.5-plus", "qwen3.8-turbo", "qwen3.8-vl-plus", "qwen3.8-coder-plus", "qwen3.8-32b",
                    "qwen3.8-plus-character", "qwen3.8-plus-garbage", "qwen3.8-plus-2026-09",
                    "qwen3.8-plus-0902-extra", "Qwen3.8-plus", " qwen3.8-plus", "qwen3.8-plus\n",
                    "deepseek-v4.1-flash", "deepseek-chat", "gpt-4o", "gpt-5", "test-model")) {
                JsonObject body = invoke(adaptation, model, null, format, null, pieces, original);
                check(body.get("response_format").equals(GSON.toJsonTree(ResponseFormat.jsonObject())),
                        "unsupported model falls back without schema on wire: " + model);
                checkSchemaPrompt(body, schema, original.size());
            }
            for (String model : List.of("gpt-4o-mini", "gpt-4o-mini-2024-07-18", "gpt-4o-2024-08-06")) {
                JsonObject body = invoke(adaptation, model, null, format, null, pieces, original);
                check(body.get("response_format").equals(GSON.toJsonTree(format)), "explicit OpenAI whitelist");
                check(body.getAsJsonArray("messages").equals(GSON.toJsonTree(original)), "OpenAI omits extra prompt");
                JsonObject jsonMode = invoke(adaptation, model, null, ResponseFormat.jsonObject(), null, pieces, original);
                check(jsonMode.getAsJsonArray("messages").size() == original.size() + 1, "explicit JSON mode adds prompt");
                check(!prompt(jsonMode).contains("字段定义："), "JSON Object does not invent a schema");
            }
            JsonObject plain = invoke(adaptation, "deepseek-v4.1-flash", null, null, null, pieces, original);
            check(plain.getAsJsonArray("messages").equals(GSON.toJsonTree(original)), "ordinary chat has no JSON prompt");
            JsonObject defaultQwen = invoke(adaptation, null, "qwen3.8-plus", format, null, pieces, original);
            check(defaultQwen.get("response_format").equals(GSON.toJsonTree(format)), "default model is resolved first");
            JsonObject override = invoke(adaptation, "deepseek-v4.1-flash", "qwen3.8-plus", format, null, pieces, original);
            check(override.get("response_format").equals(GSON.toJsonTree(ResponseFormat.jsonObject())), "override wins");
            JsonObject reverseOverride = invoke(adaptation, "qwen3.8-plus", "deepseek-chat", format, null, pieces, original);
            check(reverseOverride.get("response_format").equals(GSON.toJsonTree(format)), "whitelisted override wins");
        }
        for (String model : List.of("qwen3.8-flash", "deepseek-v4.1-flash")) {
            JsonObject first = invoke("dashscope", model, null, format, null, pieces, original);
            Message image = new Message();
            image.role = "user";
            image.content.add(new ImageUrlPiece("https://example.invalid/image.png"));
            List<Message> withImage = new ArrayList<>(original);
            withImage.add(image);
            JsonObject imageRound = invoke("dashscope", model, null, format, null, pieces, withImage);
            Message assistant = new Message("");
            assistant.role = "assistant";
            var call = new com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Tool_call();
            call.id = "call-probe";
            call.function.name = "probe";
            call.function.arguments = "{}";
            assistant.tool_calls = new com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Tool_call[]{call};
            Message toolResult = new Message("工具结果");
            toolResult.role = "tool";
            toolResult.tool_call_id = call.id;
            List<Message> continued = new ArrayList<>(withImage);
            continued.add(assistant);
            continued.add(toolResult);
            JsonObject toolRound = invoke("dashscope", model, null, format, List.of(
                    LLMFunction.builder().name("probe").parameters(JsonSchema.object()).build()), pieces, continued);
            check(prompt(first).equals(prompt(imageRound)) && prompt(first).equals(prompt(toolRound)),
                    "prompt is present from the first request and stable across image/tool rounds: " + model);
            checkSchemaPrompt(toolRound, schema, continued.size());
            check(toolRound.getAsJsonArray("messages").get(3).equals(GSON.toJsonTree(image)), "image is unchanged");
            check(toolRound.getAsJsonArray("messages").get(4).equals(GSON.toJsonTree(assistant)), "tool call is unchanged");
            JsonObject repeat = invoke("dashscope", model, null, format, null, pieces, original);
            check(repeat.getAsJsonArray("messages").equals(first.getAsJsonArray("messages")), "no accumulated prompts");
        }
    }

    private static String prompt(JsonObject body) {
        JsonObject first = body.getAsJsonArray("messages").get(0).getAsJsonObject();
        check(first.get("role").getAsString().equals("system"), "injected message is first and system role");
        return first.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    private static void checkSchemaPrompt(JsonObject body, JsonSchema schema, int originalSize) {
        check(body.getAsJsonArray("messages").size() == originalSize + 1, "exactly one prompt is injected");
        String text = prompt(body);
        String marker = "字段定义：\n";
        check(JsonParser.parseString(text.substring(text.indexOf(marker) + marker.length()).strip())
                .equals(GSON.toJsonTree(schema)), "prompt preserves the entire caller schema");
        check(!text.contains("不增加未定义字段") && !text.contains("不得省略或替换为 null"),
                "generic instructions do not override additionalProperties or nullable definitions");
    }

    private static JsonObject invoke(String adaptation, ResponseFormat format, List<LLMFunction> tools,
                                     String[] pieces) throws Exception {
        return invoke(adaptation, "gpt-4o-mini", null, format, tools, pieces, List.of(new Message("Return JSON")));
    }

    private static JsonObject invoke(String adaptation, String model, String defaultModel, ResponseFormat format,
                                     List<LLMFunction> tools, String[] pieces, List<Message> messages) throws Exception {
        String originalMessages = GSON.toJson(messages);
        String originalFormat = GSON.toJson(format);
        AtomicReference<JsonObject> sent = new AtomicReference<>();
        OpenAIChannel channel = new OpenAIChannel();
        channel.logger = LoggerFactory.getLogger(ResponseFormatTest.class);
        channel.baseurl = "https://example.invalid/v1";
        channel.apikey = "offline-test";
        channel.speciallyAdaptation = adaptation;
        channel.defaultModel = defaultModel;
        channel.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            sent.set(JsonParser.parseString(buffer.readUtf8()).getAsJsonObject());
            check(chain.request().url().encodedPath().equals("/v1/chat/completions"), "chat endpoint is unchanged");
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(ResponseBody.create(sse(pieces), MediaType.get("text/event-stream"))).build();
        }).build();
        CompletionsRequest request = new CompletionsRequest();
        request.responseFormat = format;
        request.toolChoice = "none";
        List<String> callbacks = new ArrayList<>();
        var response = channel.completions(model, messages, tools,
                request, callbacks::add);
        check(originalMessages.equals(GSON.toJson(messages)), "caller messages are not mutated");
        check(originalFormat.equals(GSON.toJson(format)) && request.responseFormat == format, "caller format is not mutated");
        check(sent.get().get("model").getAsString().equals(model == null ? defaultModel : model), "effective model is used");
        String expected = String.join("", pieces);
        check(response.choices[0].message.content.equals(expected), "return generated content verbatim");
        check(String.join("", callbacks).equals(expected), "callback preserves generated content");
        check(callbacks.equals(Arrays.stream(pieces).filter(piece -> !piece.isEmpty()).toList()),
                "callbacks include all nonempty whitespace fragments");
        check(response.choices[0].finish_reason.equals("stop"), "finish_reason is preserved");
        check(sent.get().get("stream").getAsBoolean(), "streaming stays enabled");
        return sent.get();
    }

    private static String sse(String[] pieces) {
        StringBuilder sse = new StringBuilder();
        for (String piece : pieces) {
            JsonObject delta = new JsonObject();
            delta.addProperty("content", piece);
            sse.append(event(delta, null));
        }
        return sse.append(event(new JsonObject(), "stop")).append("data: [DONE]\n\n").toString();
    }

    private static String event(JsonObject delta, String finishReason) {
        JsonObject choice = new JsonObject();
        choice.add("delta", delta);
        if (finishReason != null) choice.addProperty("finish_reason", finishReason);
        JsonObject data = new JsonObject();
        var choices = new com.google.gson.JsonArray();
        choices.add(choice);
        data.add("choices", choices);
        return "data: " + GSON.toJson(data) + "\n\n";
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
