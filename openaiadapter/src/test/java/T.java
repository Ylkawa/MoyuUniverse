import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;

public class T {
    public static void main(String[] args) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .build();

        RequestBody requestBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), "{\n" +
                "    \"model\": \"deepseek-chat\",\n" +
                "    \"messages\": [\n" +
                "        {\"role\": \"user\", \"content\": \"哪个虚拟歌手的头发颜色是葱绿色的？\"}\n" +
                "    ],\n" +
                "    \"stream\": true,\n" +
                "    \"stream_options\": {\"include_usage\": true}\n" +
                "}");

        Request request = new Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .addHeader("Authorization", "Bearer "+System.getenv("key"))
                .post(requestBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            BufferedSource source = response.body().source();

            String line;
            while ((line = source.readUtf8Line()) != null) {
                System.out.println("收到一行：" + line);
            }
        }
    }
}
