package com.nekoyu.Universe.DeepSeekAdapter;

import okhttp3.*;

import java.io.IOException;

public class TEST {
    Assistant dsa = new Assistant(new DeepSeekChannel("","",""));
    public void d() {
        dsa.addFunction(new DeepSeekFunction("", "", new DeepSeekFunction.CallbackFunction() {
            @Override
            public String function(String[] args) {
                return "";
            }
        }));
    }
    public static void main(String[] args) {
//        Gson gson = new Gson();
//        String json = "{\n" +
//                "  \"id\": \"2a6b0e12-91f6-4a7e-91bd-8c556f360864\",\n" +
//                "  \"object\": \"chat.completion\",\n" +
//                "  \"created\": 1753637997,\n" +
//                "  \"model\": \"deepseek-chat\",\n" +
//                "  \"choices\": [\n" +
//                "    {\n" +
//                "      \"index\": 0,\n" +
//                "      \"message\": {\n" +
//                "        \"role\": \"assistant\",\n" +
//                "        \"content\": \"\",\n" +
//                "        \"tool_calls\": [\n" +
//                "          {\n" +
//                "            \"index\": 0,\n" +
//                "            \"id\": \"call_0_472ba5af-3809-4cfb-bf1a-2e3c862caab7\",\n" +
//                "            \"type\": \"function\",\n" +
//                "            \"function\": {\n" +
//                "              \"name\": \"get_weather\",\n" +
//                "              \"arguments\": \"{\\\"location\\\":\\\"Hangzhou\\\"}\"\n" +
//                "            }\n" +
//                "          }\n" +
//                "        ]\n" +
//                "      },\n" +
//                "      \"logprobs\": null,\n" +
//                "      \"finish_reason\": \"tool_calls\"\n" +
//                "    }\n" +
//                "  ],\n" +
//                "  \"usage\": {\n" +
//                "    \"prompt_tokens\": 136,\n" +
//                "    \"completion_tokens\": 21,\n" +
//                "    \"total_tokens\": 157,\n" +
//                "    \"prompt_tokens_details\": {\n" +
//                "      \"cached_tokens\": 0\n" +
//                "    },\n" +
//                "    \"prompt_cache_hit_tokens\": 0,\n" +
//                "    \"prompt_cache_miss_tokens\": 136\n" +
//                "  },\n" +
//                "  \"system_fingerprint\": \"fp_8802369eaa_prod0623_fp8_kvcache\"\n" +
//                "}";
//        AssistantResponse ar = gson.fromJson(json, AssistantResponse.class);
//        System.out.println(ar.choices[0].message.tool_calls[0].function.arguments.get("location"));
        String data = "{\n" +
                "    \"model\": \"deepseek-chat\",\n" +
                "    \"messages\": [\n" +
                "        {\"role\": \"user\", \"content\": \"How's the weather in Hangzhou?\"}\n" +
                "    ],\n" +
                "    \"tools\": [\n" +
                "        {\n" +
                "            \"type\": \"function\",\n" +
                "            \"function\": {\n" +
                "                \"name\": \"get_weather\",\n" +
                "                \"description\": \"Get weather of an location, the user shoud supply a location first\",\n" +
                "                \"parameters\": {\n" +
                "                    \"type\": \"object\",\n" +
                "                    \"properties\": {\n" +
                "                        \"location\": {\n" +
                "                            \"type\": \"string\",\n" +
                "                            \"description\": \"The city and state, e.g. San Francisco, CA\"\n" +
                "                        }\n" +
                "                    },\n" +
                "                    \"required\": [\"location\"]\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "    ]\n" +
                "}";

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url("https://api.deepseek.com/v1/chat/completions")
                .post(RequestBody.create(MediaType.get("application/json; charset=utf-8"), data))
                .addHeader("Authorization", "Bearer sk-d3462fce53414cc2811be71b1ff6aadd")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            // 获取响应体
            String responseData = response.body().string();
            System.out.println(responseData);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
