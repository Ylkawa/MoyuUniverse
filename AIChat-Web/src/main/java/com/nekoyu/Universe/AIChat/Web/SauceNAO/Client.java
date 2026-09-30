package com.nekoyu.Universe.AIChat.Web.SauceNAO;

import com.google.gson.Gson;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

/** SauceNAO 图片来源查询；相似度不代表角色身份确认。 */
public class Client {
    public static class QuotaException extends IOException {
        public final boolean longExhausted;
        public QuotaException(boolean longExhausted) {
            super("SauceNAO quota exhausted");
            this.longExhausted = longExhausted;
        }
    }
    private final String apiKey;
    private final OkHttpClient client;
    private final Gson gson = new Gson();

    public Client(String apiKey) {
        this(apiKey, null);
    }

    public Client(String apiKey, Proxy proxy) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("未配置 SauceNAO API Key");
        }
        this.apiKey = apiKey.trim();
        client = new OkHttpClient.Builder().proxy(proxy)
                .connectTimeout(15, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).build();
    }

    /** 将 API 响应反序列化为响应对象；每次只查询一次。 */
    public SearchResponse search(String imageUrl) throws IOException {
        HttpUrl image = imageUrl == null ? null : HttpUrl.parse(imageUrl);
        if (image == null || !image.username().isEmpty() || !image.password().isEmpty()) {
            throw new IllegalArgumentException("请输入 HTTP/HTTPS 图片直链，不能包含登录凭据");
        }
        Request request = new Request.Builder().url("https://saucenao.com/search.php")
                .header("Accept", "application/json")
                .post(new FormBody.Builder().add("api_key", apiKey).add("output_type", "2")
                        .add("db", "999").add("numres", "5").add("url", image.toString()).build())
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 429) throw new QuotaException(false);
                if ("challenge".equalsIgnoreCase(response.header("cf-mitigated"))) {
                    throw new IOException("SauceNAO 被 Cloudflare 验证拦截（HTTP " + response.code()
                            + "），当前代理出口无法直接访问 API");
                }
                throw new IOException("SauceNAO HTTP " + response.code()
                        + (response.code() == 429 ? "：请求过快或配额耗尽" : ""));
            }
            if (response.body() == null) throw new IOException("SauceNAO 响应为空");
            SearchResponse result;
            try {
                result = gson.fromJson(response.body().string().replace(apiKey, "[REDACTED]"), SearchResponse.class);
                if (result == null || result.header == null || result.header.status == null)
                    throw new IllegalStateException();
                if (result.header.status < 0) {
                    if (Integer.valueOf(0).equals(result.header.short_remaining)
                            || Integer.valueOf(0).equals(result.header.long_remaining))
                        throw new QuotaException(Integer.valueOf(0).equals(result.header.long_remaining));
                    throw new IOException("SauceNAO 请求失败："
                            + (result.header.message != null ? result.header.message : "未知 API 错误"));
                }
            } catch (RuntimeException error) {
                throw new IOException("SauceNAO 返回无效的 API JSON", error);
            }
            // 正数 status 为部分索引失败，保留该状态及可用结果供调用方判断。
            return result;
        }
    }
}
