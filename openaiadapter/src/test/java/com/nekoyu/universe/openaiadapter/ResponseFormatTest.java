package com.nekoyu.universe.openaiadapter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.JsonSchema;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ResponseFormat;
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

/** Run directly; the interceptor supplies SSE without contacting a model API. */
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
        // Include a space inside a JSON string and standalone formatting whitespace.
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
        // The transport must return even invalid/truncated JSON verbatim for the caller to handle.
        invoke("none", structured, null, new String[]{"{\"answer\":", " "});
        System.out.println("ResponseFormatTest passed");
    }

    private static JsonObject invoke(String adaptation, ResponseFormat format, List<LLMFunction> tools,
                                     String[] pieces) throws Exception {
        AtomicReference<JsonObject> sent = new AtomicReference<>();
        OpenAIChannel channel = new OpenAIChannel();
        channel.logger = LoggerFactory.getLogger(ResponseFormatTest.class);
        channel.baseurl = "https://example.invalid/v1";
        channel.apikey = "offline-test";
        channel.speciallyAdaptation = adaptation;
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
        var response = channel.completions("test-model", List.of(new Message("Return JSON")), tools,
                request, callbacks::add);
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
