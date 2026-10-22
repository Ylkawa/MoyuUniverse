package com.nekoyu.universe.openaiadapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.EmbeddingRequest;
import okhttp3.*;
import okio.Buffer;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Offline HTTP interception; run main directly without contacting an embedding service. */
public class EmbeddingDimensionsTest {
    public static void main(String[] args) {
        verifyTextRequest(2, 1, "research-model");
        verifyTextRequest(null, 1, null);
        verifyTextRequest(2, 11, "research-model");
        verifyRejectedOptions();
        System.out.println("EmbeddingDimensionsTest passed (dimensions, defaults, batching, unsupported options)");
    }

    private static void verifyTextRequest(Integer dimension, int count, String model) {
        List<JsonObject> sent = new CopyOnWriteArrayList<>();
        OpenAIChannel channel = channel(sent);
        EmbeddingRequest request = new EmbeddingRequest();
        request.model = model;
        request.dimension = dimension;
        request.spaceVersion = "local-only";
        for (int i = 0; i < count; i++) request.message.add(new MFChain("研究主题 " + i));

        var response = channel.embedding(request);
        check(sent.size() == (count + 9) / 10, "all batches must reach the HTTP adapter");
        check(response.data.size() == count, "one vector per topic");
        for (int i = 0; i < count; i++) {
            check(response.data.get(i) != null && response.data.get(i).index == i, "global vector index");
            check(response.data.get(i).embedding.length == 2, "returned vector dimension");
            check(response.data.get(i).embedding[0] == i, "vector order across batches");
        }
        for (JsonObject body : sent) {
            check(body.get("model").getAsString().equals(model == null ? "default-model" : model), "model selection");
            if (dimension == null) check(!body.has("dimensions"), "omit unspecified dimensions");
            else check(body.has("dimensions") && body.get("dimensions").getAsInt() == dimension,
                    "map dimension to the OpenAI-compatible dimensions field in every batch");
            check(!body.has("dimension") && !body.has("spaceVersion") && !body.has("inputs"),
                    "do not send internal or multimodal fields");
        }
        check(request.dimension == dimension && request.message.size() == count, "preserve caller request");
    }

    private static void verifyRejectedOptions() {
        List<JsonObject> sent = new CopyOnWriteArrayList<>();
        OpenAIChannel channel = channel(sent);
        for (int option = 0; option < 3; option++) {
            EmbeddingRequest request = new EmbeddingRequest();
            request.message.add(new MFChain("主题"));
            request.dimension = 2;
            if (option == 0) request.inputs.add(List.of());
            if (option == 1) request.enableFusion = true;
            if (option == 2) request.resolutionLevel = 1;
            try {
                channel.embedding(request);
                throw new AssertionError("unsupported option must be rejected: " + option);
            } catch (UnsupportedOperationException expected) {
                check(expected.getMessage().contains("inputs/enableFusion/resolutionLevel"),
                        "error identifies unsupported options");
            }
        }
        check(sent.isEmpty(), "unsupported requests never reach HTTP");
    }

    private static OpenAIChannel channel(List<JsonObject> sent) {
        OpenAIChannel channel = new OpenAIChannel();
        channel.logger = LoggerFactory.getLogger(EmbeddingDimensionsTest.class);
        channel.baseurl = "https://example.invalid/v1";
        channel.apikey = "offline-test";
        channel.defaultEmbeddingModel = "default-model";
        channel.client = new OkHttpClient.Builder().addInterceptor(chain -> {
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            JsonObject body = JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
            sent.add(body);
            check(chain.request().url().encodedPath().equals("/v1/embeddings"), "embedding endpoint");
            JsonArray data = new JsonArray();
            JsonArray input = body.getAsJsonArray("input");
            for (int i = 0; i < input.size(); i++) {
                String text = input.get(i).getAsString();
                check(text.startsWith("研究主题 "), "input contains plain topic text");
                int topic = Integer.parseInt(text.substring("研究主题 ".length()));
                JsonObject item = new JsonObject();
                item.addProperty("index", i);
                item.add("embedding", JsonParser.parseString("[" + topic + ",1]"));
                data.add(item);
            }
            JsonObject result = new JsonObject();
            result.add("data", data);
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body(ResponseBody.create(result.toString(), MediaType.get("application/json"))).build();
        }).build();
        return channel;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
