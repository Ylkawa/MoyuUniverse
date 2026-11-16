package com.nekoyu.Universe.AIChat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.universe.openaiadapter.OpenAIChannel;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 加密思维链回传的全链路循环测试：真实 OpenAIChannel + ChatContext 工具循环状态机，
 * 用 OkHttp 拦截器按轮次注入脚本化 SSE 并捕获每一轮真实请求体，不调用真实模型 API。
 * <p>
 * 覆盖：百炼 qwen4-max 密文帧收取与跨轮/跨回合原样回传、Gemini thought_signature
 * 按 index 保留与回传、不兼容模型/普通消息的字段省略、GPT 特调按请求模型判定。
 * <p>
 * client/logger/defaultModel 为适配器包私有字段，这里以反射注入测试替身。
 */
class EncryptedReasoningLoopTest {
    private static final String BAILIAN_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai";

    /** 百炼 qwen4-max：两轮工具调用 + 收官 + 第二个用户回合，每段密文随所属 assistant 消息原样回传 */
    @Test void bailianQwen4MaxReplaysCiphertextAcrossToolRoundsAndTurns() throws IOException {
        List<String> script = List.of(
                // 第 1 轮：摘要增量（含空白片段）→ 独立密文帧（无 content，不得被过滤）→ 工具调用
                chunk("{\"reasoning_content\":\"thinking-1\"}", null)
                        + chunk("{\"reasoning_content\":\"\\n\"}", null)
                        + chunk("{\"reasoning_content\":\"part2\"}", null)
                        + chunk("{\"encrypted_content\":\"CIPHER-1\"}", null)
                        + chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"query\\\":\\\"a\\\"}\"}}]}", null)
                        + chunk("{}", "tool_calls"),
                // 第 2 轮：新的思考段与新的密文
                chunk("{\"reasoning_content\":\"thinking-2\"}", null)
                        + chunk("{\"encrypted_content\":\"CIPHER-2\"}", null)
                        + chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call_2\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"query\\\":\\\"b\\\"}\"}}]}", null)
                        + chunk("{}", "tool_calls"),
                // 第 3 轮：收官
                chunk("{\"reasoning_content\":\"thinking-3\"}", null)
                        + chunk("{\"encrypted_content\":\"CIPHER-3\"}", null)
                        + chunk("{\"content\":\"final answer\"}", "stop"),
                // 第二个用户回合
                chunk("{\"content\":\"second answer\"}", "stop"));
        List<JsonObject> bodies = new ArrayList<>();
        OpenAIChannel channel = scriptedChannel(BAILIAN_URL, script, bodies);

        ChatAssistant assistant = new ChatAssistant(channel, "qwen4-max");
        CompletionsRequest request = new CompletionsRequest();
        request.enable_thinking = true;
        assistant.setCompletionsRequest(request);
        assistant.addTool(LLMFunction.builder().name("lookup").syncCallback(args ->
                new Message("result:" + args.getAsJsonObject().get("query").getAsString())).build());
        ChatContext context = assistant.getChatContext();

        context.userMsg(new Message("question-1"));
        StringBuilder output = new StringBuilder();
        CompletionsResponse first = assistant.completions(output::append);
        assertEquals("final answer", first.choices[0].message.content);
        assertEquals("final answer", output.toString());
        assertEquals("thinking-1\npart2thinking-2thinking-3", first.choices[0].message.reasoning_content,
                "whitespace reasoning fragments are preserved");
        assertEquals("CIPHER-3", first.choices[0].message.encrypted_content);

        context.userMsg(new Message("question-2"));
        CompletionsResponse second = assistant.completions(null);
        assertEquals("second answer", second.choices[0].message.content);

        assertEquals(4, bodies.size(), "three tool-loop rounds plus the second user turn");
        for (JsonObject body : bodies) {
            assertTrue(body.get("enable_thinking").getAsBoolean());
            assertEquals("summarized", body.get("thinking_display").getAsString(),
                    "summary+encryption must be enabled on every round");
            assertTrue(body.get("preserve_thinking").getAsBoolean());
        }
        // 首轮：没有历史 assistant，任何消息都不得携带密文字段
        assertTrue(messagesOf(bodies.get(0)).stream().noneMatch(m -> m.has("encrypted_content")));

        // 第 2 轮请求：第 1 段密文随其 assistant 消息回传，摘要与工具参数完整
        JsonObject body1 = bodies.get(1);
        JsonObject assistant1 = assistantWithCipher(body1, "CIPHER-1");
        assertNotNull(assistant1, "round-1 ciphertext is replayed in round 2");
        assertEquals("thinking-1\npart2", assistant1.get("reasoning_content").getAsString());
        assertEquals("call_1", assistant1.getAsJsonArray("tool_calls").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("{\"query\":\"a\"}", assistant1.getAsJsonArray("tool_calls").get(0).getAsJsonObject()
                .getAsJsonObject("function").get("arguments").getAsString());
        assertTrue(toolResultFollows(body1, "call_1", "result:a"));

        // 第 3 轮请求：两段密文各自跟随所属 assistant 消息，顺序稳定
        JsonObject body2 = bodies.get(2);
        assertNotNull(assistantWithCipher(body2, "CIPHER-1"));
        JsonObject assistant2 = assistantWithCipher(body2, "CIPHER-2");
        assertNotNull(assistant2, "each thinking segment keeps its own ciphertext");
        assertEquals("call_2", assistant2.getAsJsonArray("tool_calls").get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(toolResultFollows(body2, "call_2", "result:b"));
        assertTrue(indexOfAssistant(body2, "CIPHER-1") < indexOfAssistant(body2, "CIPHER-2"));

        // 第二个用户回合：全部三段历史密文仍在，收官密文跟随最终回答
        JsonObject body3 = bodies.get(3);
        assertNotNull(assistantWithCipher(body3, "CIPHER-1"));
        assertNotNull(assistantWithCipher(body3, "CIPHER-2"));
        JsonObject assistant3 = assistantWithCipher(body3, "CIPHER-3");
        assertNotNull(assistant3);
        assertEquals("final answer", textOf(assistant3));
        List<JsonObject> messages3 = messagesOf(body3);
        assertEquals("user", messages3.get(messages3.size() - 1).get("role").getAsString());
        assertEquals("question-2", textOf(messages3.get(messages3.size() - 1)));
        // tool 消息与普通 user 消息不得携带密文
        assertTrue(messages3.stream().filter(m -> !"assistant".equals(m.get("role").getAsString()))
                .noneMatch(m -> m.has("encrypted_content")));
    }

    /** Gemini：并行工具调用仅首个 call 带签名，须按 index 原样回传；文本轮 message 级签名同样回传 */
    @Test void geminiThoughtSignaturesSurviveToolLoopAndTextTurns() throws IOException {
        List<String> script = List.of(
                // 第 1 轮：并行工具调用，签名只在 index 0 上
                chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call_p\",\"type\":\"function\","
                        + "\"extra_content\":{\"google\":{\"thought_signature\":\"SIG-A\"}},"
                        + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"query\\\":\\\"paris\\\"}\"}}]}", null)
                        + chunk("{\"tool_calls\":[{\"index\":1,\"id\":\"call_l\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"query\\\":\\\"london\\\"}\"}}]}", null)
                        + chunk("{}", "tool_calls"),
                // 第 2 轮：文本收官，携带 message 级签名
                chunk("{\"extra_content\":{\"google\":{\"thought_signature\":\"SIG-TEXT\"}}}", null)
                        + chunk("{\"content\":\"weather answer\"}", "stop"),
                // 第二个用户回合
                chunk("{\"content\":\"ok\"}", "stop"));
        List<JsonObject> bodies = new ArrayList<>();
        OpenAIChannel channel = scriptedChannel(GEMINI_URL, script, bodies);

        ChatAssistant assistant = new ChatAssistant(channel, "gemini-3-pro-preview");
        assistant.addTool(LLMFunction.builder().name("lookup").syncCallback(args ->
                new Message("result:" + args.getAsJsonObject().get("query").getAsString())).build());
        ChatContext context = assistant.getChatContext();
        context.userMsg(new Message("weather?"));
        assertEquals("weather answer", assistant.completions(null).choices[0].message.content);
        context.userMsg(new Message("thanks"));
        assertEquals("ok", assistant.completions(null).choices[0].message.content);

        assertEquals(3, bodies.size());
        for (JsonObject body : bodies) {
            assertFalse(body.has("thinking_display"), "Gemini gets no Bailian thinking params");
            assertFalse(body.has("preserve_thinking"));
            assertFalse(body.has("enable_thinking"));
            assertTrue(messagesOf(body).stream().noneMatch(m -> m.has("encrypted_content")));
        }
        // 第 2 轮请求：首个 call 的签名原样回传，第二个 call 不携带 extra_content
        JsonObject toolAssistant = messagesOf(bodies.get(1)).stream()
                .filter(m -> m.has("tool_calls")).findFirst().orElseThrow();
        JsonArray calls = toolAssistant.getAsJsonArray("tool_calls");
        assertEquals("SIG-A", calls.get(0).getAsJsonObject().getAsJsonObject("extra_content")
                .getAsJsonObject("google").get("thought_signature").getAsString());
        assertFalse(calls.get(1).getAsJsonObject().has("extra_content"),
                "parallel call without signature must not invent one");
        assertTrue(toolResultFollows(bodies.get(1), "call_p", "result:paris"));
        assertTrue(toolResultFollows(bodies.get(1), "call_l", "result:london"));

        // 第二个用户回合：工具轮签名与文本轮 message 级签名都在
        JsonObject body2 = bodies.get(2);
        JsonObject replayedToolAssistant = messagesOf(body2).stream()
                .filter(m -> m.has("tool_calls")).findFirst().orElseThrow();
        assertEquals("SIG-A", replayedToolAssistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject()
                .getAsJsonObject("extra_content").getAsJsonObject("google").get("thought_signature").getAsString());
        JsonObject textAssistant = messagesOf(body2).stream()
                .filter(m -> "assistant".equals(m.get("role").getAsString()) && !m.has("tool_calls"))
                .findFirst().orElseThrow();
        assertEquals("SIG-TEXT", textAssistant.getAsJsonObject("extra_content")
                .getAsJsonObject("google").get("thought_signature").getAsString());
        assertEquals("weather answer", textOf(textAssistant));
    }

    /** 百炼旧模型：开启思考但不支持摘要加密，不得下发 thinking_display / preserve_thinking */
    @Test void bailianOlderModelOmitsSummaryParams() throws IOException {
        List<String> script = List.of(chunk("{\"reasoning_content\":\"plain reasoning\"}", null)
                + chunk("{\"content\":\"ok\"}", "stop"));
        List<JsonObject> bodies = new ArrayList<>();
        OpenAIChannel channel = scriptedChannel(BAILIAN_URL, script, bodies);
        CompletionsRequest request = new CompletionsRequest();
        request.enable_thinking = true;
        CompletionsResponse response = channel.completions("qwen3-max", List.of(new Message("hi")), null, request, null);
        assertEquals("plain reasoning", response.choices[0].message.reasoning_content);
        assertNull(response.choices[0].message.encrypted_content);
        JsonObject body = bodies.get(0);
        assertTrue(body.get("enable_thinking").getAsBoolean());
        assertFalse(body.has("thinking_display"));
        assertFalse(body.has("preserve_thinking"));
        assertTrue(messagesOf(body).stream().noneMatch(m -> m.has("encrypted_content") || m.has("extra_content")));
    }

    /** GPT 特调按每次请求的实际模型判定，与 defaultModel 注入顺序无关；非思考模型不带 reasoning */
    @Test void gptAdaptationFollowsRequestModelRegardlessOfInjectionOrder() throws IOException {
        List<String> script = List.of(
                chunk("{\"content\":\"ok\"}", "stop"),
                chunk("{\"content\":\"ok\"}", "stop"),
                chunk("{\"content\":\"ok\"}", "stop"));
        List<JsonObject> bodies = new ArrayList<>();
        OpenAIChannel channel = scriptedChannel("https://api.openai.com/v1", script, bodies);
        // defaultModel 在 setBaseurl 之后注入，旧实现会漏掉特调
        setField(channel, "defaultModel", "gpt-5");
        CompletionsRequest request = new CompletionsRequest();
        request.enable_thinking = true;
        channel.completions(null, List.of(new Message("hi")), null, request, null);
        channel.completions("o3-mini", List.of(new Message("hi")), null, request, null);
        channel.completions("gpt-4o", List.of(new Message("hi")), null, request, null);
        assertEquals("low", bodies.get(0).getAsJsonObject("reasoning").get("effort").getAsString());
        assertEquals("low", bodies.get(1).getAsJsonObject("reasoning").get("effort").getAsString());
        assertFalse(bodies.get(2).has("reasoning"), "non-reasoning GPT model gets no reasoning param");
    }

    // ---------- 脚本化通道与断言工具 ----------

    /** 以拦截器按轮次返回脚本 SSE，并捕获每一轮真实请求体 */
    private static OpenAIChannel scriptedChannel(String baseUrl, List<String> script, List<JsonObject> bodies) {
        OpenAIChannel channel = new OpenAIChannel();
        setField(channel, "logger", LoggerFactory.getLogger("encrypted-reasoning-loop-test"));
        channel.setApiKey("offline-test");
        channel.setBaseurl(baseUrl);
        AtomicInteger round = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            bodies.add(JsonParser.parseString(buffer.readUtf8()).getAsJsonObject());
            int index = round.getAndIncrement();
            if (index >= script.size()) throw new IOException("unexpected extra request #" + (index + 1));
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(ResponseBody.create(script.get(index), MediaType.get("text/event-stream"))).build();
        }).build();
        setField(channel, "client", client);
        return channel;
    }

    private static void setField(OpenAIChannel channel, String name, Object value) {
        try {
            Field field = OpenAIChannel.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(channel, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法注入测试字段 " + name, e);
        }
    }

    /** 一个 SSE chunk；finish 为 null 时省略 finish_reason */
    private static String chunk(String deltaJson, String finish) {
        JsonObject choice = new JsonObject();
        choice.add("delta", JsonParser.parseString(deltaJson));
        if (finish != null) choice.addProperty("finish_reason", finish);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject root = new JsonObject();
        root.add("choices", choices);
        return "data: " + root + "\n\n";
    }

    private static List<JsonObject> messagesOf(JsonObject body) {
        List<JsonObject> messages = new ArrayList<>();
        body.getAsJsonArray("messages").forEach(m -> messages.add(m.getAsJsonObject()));
        return messages;
    }

    private static JsonObject assistantWithCipher(JsonObject body, String cipher) {
        return messagesOf(body).stream()
                .filter(m -> "assistant".equals(m.get("role").getAsString())
                        && m.has("encrypted_content") && cipher.equals(m.get("encrypted_content").getAsString()))
                .findFirst().orElse(null);
    }

    private static int indexOfAssistant(JsonObject body, String cipher) {
        List<JsonObject> messages = messagesOf(body);
        for (int i = 0; i < messages.size(); i++) {
            JsonObject m = messages.get(i);
            if ("assistant".equals(m.get("role").getAsString()) && m.has("encrypted_content")
                    && cipher.equals(m.get("encrypted_content").getAsString())) return i;
        }
        return -1;
    }

    /** assistant(tool_calls) 之后存在对应 tool_call_id 且内容匹配的工具结果 */
    private static boolean toolResultFollows(JsonObject body, String callId, String expectedText) {
        List<JsonObject> messages = messagesOf(body);
        for (int i = 0; i < messages.size(); i++) {
            JsonObject m = messages.get(i);
            if ("tool".equals(m.get("role").getAsString()) && callId.equals(m.get("tool_call_id").getAsString())
                    && textOf(m).contains(expectedText)) {
                return messages.subList(0, i).stream().anyMatch(prev ->
                        "assistant".equals(prev.get("role").getAsString()) && prev.has("tool_calls"));
            }
        }
        return false;
    }

    /** 拼接消息 content 数组中的文本片段 */
    private static String textOf(JsonObject message) {
        StringBuilder sb = new StringBuilder();
        message.getAsJsonArray("content").forEach(piece -> {
            JsonObject p = piece.getAsJsonObject();
            if (p.has("text")) sb.append(p.get("text").getAsString());
        });
        return sb.toString();
    }
}
