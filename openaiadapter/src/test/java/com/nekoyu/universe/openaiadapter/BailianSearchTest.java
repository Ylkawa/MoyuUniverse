package com.nekoyu.universe.openaiadapter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nekoyu.Universe.Universe;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** 可直接运行；使用真正的配置加载入口和拦截器，不调用真实模型 API。 */
public class BailianSearchTest {
    private static final Gson GSON = new Gson();
    private static final String BAILIAN_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String NEW_BAILIAN_URL = "https://maas.qianwenaiapi.com/compatible-mode/v1";
    private static final String ANSWER = "{\"answer\":\"ok\"}";
    private static final String SSE = "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"answer\\\":\\\"ok\\\"}\"},"
            + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
    private static int requests;

    public static void main(String[] args) throws Exception {
        try (ConfigFixture fixture = new ConfigFixture()) {
            for (Boolean search : new Boolean[]{null, false}) {
                OpenAIChannel channel = fixture.load(BAILIAN_URL, "qwen3-max", search);
                assertSearch(invoke(channel, null), false, "Search omitted or false");
            }

            OpenAIChannel enabled = fixture.load(BAILIAN_URL, "qwen3-max", true);
            for (String model : List.of(
                    "qwen3-max", "qwen3.5-plus", "qwen3.5.1-plus", "qwen4-plus", "qwen42.7-flash",
                    "qwen3.7-max-preview", "qwen3-max-2025-09-23", "qwen3.8-max-0902",
                    "qwen3-plus-latest", "qwen3-flash-character-2026-02-26",
                    "qwen-max", "qwen-plus", "qwen-flash", "qwen-turbo", "qwen-plus-latest",
                    "qwen-max-preview-2025-09-23", "qwen-flash-character",
                    "deepseek-r1", "deepseek-r1-0528", "deepseek-v3", "deepseek-v3.2",
                    "deepseek-v3.2-exp", "deepseek-v4-pro", "deepseek-v4-pro-0813",
                    "deepseek-v4-flash-0731", "deepseek-v5.1-pro-2027-02-03")) {
                assertSearch(invoke(enabled, model), true, model);
            }

            for (String model : List.of(
                    "qwen-3-max", "qwen-v3-plus", "qwen3.5-27b", "qwen3-235b-a22b",
                    "qwen3-coder", "qwen3-coder-plus", "qwen3-omni-flash", "qwen3-vl-plus",
                    "qwen-plus-coder", "qwen-image-2.0-pro", "qwen-vl-max", "text-embedding-v4",
                    "qwen3-max-garbage", "qwen3-max-2025-09", "qwen3-max-2025-09-23-extra",
                    "qwen3-max\n", " qwen3-max", "Qwen3-max", "qwen3-max|plus",
                    "deepseek-chat", "deepseek-reasoner", "deepseek-r1-distill-qwen-32b",
                    "deepseek-v3.2-distill", "deepseek-v3.2-exp-extra", "deepseek-v", "deepseek-v3x2",
                    "DeepSeek-v3.2", "unknown-model", "")) {
                assertSearch(invoke(enabled, model), false, model);
            }

            assertSearch(invoke(enabled, null), true, "supported default model");
            OpenAIChannel unsupportedDefault = fixture.load(BAILIAN_URL, "unknown-model", true);
            assertSearch(invoke(unsupportedDefault, null), false, "unsupported default model");
            assertSearch(invoke(unsupportedDefault, "qwen3-max"), true, "supported request override");
            OpenAIChannel missingModel = fixture.load(BAILIAN_URL, null, true);
            assertSearch(invoke(missingModel, null), false, "missing model");

            for (String url : List.of(NEW_BAILIAN_URL,
                    "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                    "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1")) {
                OpenAIChannel channel = fixture.load(url, "qwen3-max", true);
                check("dashscope".equals(channel.speciallyAdaptation), "recognize Bailian URL: " + url);
                assertSearch(invoke(channel, null), true, url);
            }

            for (String url : List.of("https://example.invalid/v1",
                    "https://maas.qianwenaiapi.com.example.invalid/v1",
                    "https://other.qianwenaiapi.com/v1")) {
                OpenAIChannel channel = fixture.load(url, "qwen3-max", true);
                check("none".equals(channel.speciallyAdaptation), "do not recognize other providers: " + url);
                assertSearch(invoke(channel, null), false, url);
            }
            OpenAIChannel gpt = fixture.load("https://example.invalid/v1", "gpt-5", true);
            assertSearch(invoke(gpt, "qwen3-max"), false, "GPT adaptation ignores Search");

            checkExistingOptions(enabled);
        }
        System.out.println("BailianSearchTest passed (" + requests + " intercepted requests)");
    }

    private static void checkExistingOptions(OpenAIChannel channel) throws Exception {
        CompletionsRequest request = new CompletionsRequest();
        request.enable_thinking = true;
        request.toolChoice = "none";
        JsonSchema schema = JsonSchema.object().property("answer", JsonSchema.string())
                .required("answer").additionalProperties(false);
        request.responseFormat = ResponseFormat.jsonSchema("result", schema, true);
        List<LLMFunction> tools = List.of(LLMFunction.builder().name("lookup")
                .parameters(JsonSchema.object().property("query", JsonSchema.string()).required("query")).build());
        String richSse = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"reasoning\"}}]}\n\n"
                + SSE.replace("data: [DONE]", "data: {\"usage\":{\"prompt_tokens\":3,"
                + "\"completion_tokens\":2,\"total_tokens\":5}}\n\ndata: [DONE]");
        JsonObject body = invoke(channel, "qwen3-max", request, tools, richSse);
        assertSearch(body, true, "Search combines with existing options");
        check(body.get("enable_thinking").getAsBoolean(), "thinking remains enabled");
        check(body.getAsJsonObject("stream_options").get("include_usage").getAsBoolean(),
                "stream usage remains enabled");
        check(body.get("response_format").equals(GSON.toJsonTree(request.responseFormat)),
                "structured output format is preserved");
        check(body.get("tool_choice").getAsString().equals("none"), "tool choice is preserved");
        JsonObject tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
        check(tool.get("type").getAsString().equals("function"), "function tool envelope is preserved");
        check(tool.get("function").equals(GSON.toJsonTree(tools.get(0))), "function definition is preserved");
    }

    private static JsonObject invoke(OpenAIChannel channel, String model) throws Exception {
        return invoke(channel, model, new CompletionsRequest(), null, SSE);
    }

    private static JsonObject invoke(OpenAIChannel channel, String model, CompletionsRequest request,
                                     List<LLMFunction> tools, String sse) throws Exception {
        AtomicReference<JsonObject> sent = new AtomicReference<>();
        channel.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            sent.set(JsonParser.parseString(buffer.readUtf8()).getAsJsonObject());
            check(chain.request().url().toString().equals(channel.baseurl + "/chat/completions"),
                    "chat completions endpoint is preserved");
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(ResponseBody.create(sse, MediaType.get("text/event-stream"))).build();
        }).build();
        List<String> callbacks = new ArrayList<>();
        var response = channel.completions(model, List.of(new Message("Return JSON")), tools, request, callbacks::add);
        requests++;
        check(ANSWER.equals(response.choices[0].message.content), "generated content is preserved");
        check(ANSWER.equals(String.join("", callbacks)), "stream callback is preserved");
        check("stop".equals(response.choices[0].finish_reason), "finish reason is preserved");
        if (sse.contains("reasoning_content")) {
            check("reasoning".equals(response.choices[0].message.reasoning_content), "reasoning is preserved");
            check(response.usage.prompt_tokens == 3 && response.usage.completion_tokens == 2
                    && response.usage.total_tokens == 5, "stream usage is parsed");
        }
        JsonObject body = sent.get();
        check(body.get("stream").getAsBoolean(), "streaming stays enabled");
        String effectiveModel = model != null ? model : channel.defaultModel;
        if (effectiveModel == null) check(!body.has("model"), "missing model is omitted");
        else check(effectiveModel.equals(body.get("model").getAsString()), "send actual request model");
        return body;
    }

    private static void assertSearch(JsonObject body, boolean enabled, String scenario) {
        if (enabled) {
            check(body.has("enable_search") && body.getAsJsonPrimitive("enable_search").isBoolean()
                    && body.get("enable_search").getAsBoolean(), "enable search: " + scenario);
        } else {
            check(!body.has("enable_search"), "omit search field entirely: " + scenario);
        }
        check(!body.has("search_options"), "use platform default search strategy");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ConfigFixture implements AutoCloseable {
        private final Path directory = Files.createTempDirectory("bailian-search-test-");
        private final Path configFile = directory.resolve("provider.json");
        private final String providerId = "offline-search-" + UUID.randomUUID();
        private final OpenAIAdapter adapter = new OpenAIAdapter() {
            @Override
            protected File getConfigDir() {
                return directory.toFile();
            }
        };

        private ConfigFixture() throws IOException {}

        private OpenAIChannel load(String baseUrl, String defaultModel, Boolean search) throws IOException {
            JsonObject config = new JsonObject();
            config.addProperty("ProviderId", providerId);
            config.addProperty("APIKey", "offline-test");
            config.addProperty("BaseUrl", baseUrl);
            if (defaultModel != null) config.addProperty("DefaultModel", defaultModel);
            if (search != null) config.addProperty("Search", search);
            Files.writeString(configFile, GSON.toJson(config));
            check(adapter.prepare(), "prepare adapter using provider JSON");
            return (OpenAIChannel) Universe.Providers.get(providerId);
        }

        @Override
        public void close() throws IOException {
            Universe.Providers.remove(providerId);
            Files.deleteIfExists(configFile);
            Files.deleteIfExists(directory);
        }
    }
}
