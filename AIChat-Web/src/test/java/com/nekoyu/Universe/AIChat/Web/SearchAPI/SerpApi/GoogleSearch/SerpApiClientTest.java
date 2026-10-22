package com.nekoyu.Universe.AIChat.Web.SearchAPI.SerpApi.GoogleSearch;

import com.nekoyu.Universe.AIChat.Web.Config;
import com.nekoyu.Universe.AIChat.Web.SearchAPI.SearchResult;
import com.sun.net.httpserver.HttpServer;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** 离线回归：不使用真实密钥，不调用外部 API。运行 main 执行所有检查。 */
public class SerpApiClientTest {
    private static final String KEY = "offline-test-key";
    private static final String SUCCESS = """
            {"search_metadata":{"status":"Success"},"organic_results":[
              {"title":"Example","link":"https://example.com/","snippet":"Summary","sitelinks":{}}
            ]}
            """;
    private static int checks;

    public static void main(String[] args) throws Exception {
        successAndRequestOptions();
        httpErrors();
        invalidResponses();
        emptyResults();
        optionalFieldsAndInvalidLinks();
        slowResponseExceedsOldDeadline();
        System.out.println("SerpApiClientTest passed: " + checks + " checks");
    }

    private static void successAndRequestOptions() throws Exception {
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 18080));
        Client client = new Client("  " + KEY + "  ", proxy);
        Config.SearchParam params = new Config.SearchParam();
        params.language = "zh-cn";
        params.country = "cn";
        params.location = "Shanghai";
        client.setSearchParam(params);
        AtomicReference<okhttp3.Request> sent = new AtomicReference<>();
        client.client = client.client.newBuilder().addInterceptor(chain -> {
            sent.set(chain.request());
            return response(chain.request(), 200, SUCCESS);
        }).build();
        SearchResult result = client.search("test & query");
        check(result.items.size() == 1 && "Summary".equals(result.items.get(0).snippet), "正常搜索结果");
        check("test & query".equals(result.resultFor), "缺少 search_information 时保留原查询");
        var url = sent.get().url();
        check("serpapi.com".equals(url.host()) && "/search.json".equals(url.encodedPath())
                && "google".equals(url.queryParameter("engine"))
                && KEY.equals(url.queryParameter("api_key"))
                && "test & query".equals(url.queryParameter("q"))
                && "zh-cn".equals(url.queryParameter("hl"))
                && "cn".equals(url.queryParameter("gl"))
                && "Shanghai".equals(url.queryParameter("location")), "请求参数及转义");
        check(proxy.equals(client.client.proxy()), "保留配置代理");
        for (String key : new String[]{null, "", "  "}) {
            try {
                new Client(key);
                throw new AssertionError("应拒绝空密钥");
            } catch (IllegalArgumentException expected) {
                checks++;
            }
        }
    }

    private static void httpErrors() throws Exception {
        expectFailure(401, "{\"error\":\"Invalid API key\"}", "401", "Invalid API key");
        expectFailure(429, "{\"error\":\"Your account has run out of searches.\"}", "429", "run out of searches");
        expectFailure(503, "<html>upstream unavailable</html>", "503");
        expectFailure(403, "", "403");
        IOException redacted = expectFailure(401, "{\"error\":\"Rejected " + KEY + "\"}", "[REDACTED]");
        check(!redacted.toString().contains(KEY), "错误消息不泄露密钥");
    }

    private static void invalidResponses() throws Exception {
        expectFailure(200, "{\"error\":\"Backend failed\"}", "Backend failed");
        expectFailure(200, "{\"search_metadata\":{\"status\":\"Error\"}}", "Error");
        expectFailure(200, "{\"search_metadata\":{\"status\":\"Processing\"}}", "Processing");
        expectFailure(200, "{\"search_metadata\":{\"status\":\"Queued\"}}", "Queued");
        expectFailure(200, "{}", "status=缺失");
        expectFailure(200, "{\"search_metadata\":{\"status\":\"Success\"}}", "organic_results");
        expectFailure(200, "{\"search_metadata\":{\"status\":\"Success\"},\"organic_results\":[],\"error\":\"Backend failed\"}", "Backend failed");
        expectFailure(200, "", "响应体为空");
        expectFailure(200, "<html>not json</html>", "JSON");
        expectFailure(200, "null", "JSON 对象");
        expectFailure(200, "[]", "JSON 对象");
        expectFailure(200, "{", "JSON");
        expectFailure(200, "{\"organic_results\":{}}", "字段类型无效");
    }

    private static void emptyResults() throws Exception {
        SearchResult result = fixture(200, """
                {"search_metadata":{"status":"Success"},
                 "search_information":{"organic_results_state":"Fully empty"},
                 "error":"Google hasn't returned any results for this query."}
                """).search("no hits");
        check(result.items.isEmpty() && "no hits".equals(result.resultFor), "官方正常空结果不算服务错误");
        result = fixture(200, "{\"search_metadata\":{\"status\":\"Success\"},\"organic_results\":[]}").search("empty");
        check(result.items.isEmpty(), "显式空结果数组");
    }

    private static void optionalFieldsAndInvalidLinks() throws Exception {
        SearchResult result = fixture(200, """
                {"search_metadata":{"status":"Success"},
                 "search_information":{"query_displayed":"corrected"},
                 "knowledge_graph":{"title":"Subject","description":"Description","breadcrumbs":[],"other":{}},
                 "organic_results":[null,{}, {"link":"javascript:void(0)"},
                   {"title":"Valid","link":"https://example.com/","sitelinks":{"expanded":[
                     null,{}, {"link":"broken"}, {"title":"Child","link":"https://example.com/child"}
                   ]}}
                 ]}
                """).search("original");
        check("corrected".equals(result.resultFor), "保留服务端更正查询");
        check("Subject: Description".equals(result.overview), "知识图谱混合字段类型");
        check(result.items.size() == 1 && ((SearchResult.SiteItem) result.items.get(0)).siteLinks.size() == 1,
                "跳过无效结果及站内链接，保留有效项");
        expectFailure(200, "{\"search_metadata\":{\"status\":\"Success\"},\"organic_results\":[null,{}, {\"link\":\"broken\"}]}", "没有有效");
    }

    private static void slowResponseExceedsOldDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            try {
                Thread.sleep(11_000);
                byte[] bytes = SUCCESS.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            Client client = new Client(KEY);
            client.client = client.client.newBuilder().addInterceptor(chain -> chain.proceed(chain.request()
                    .newBuilder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/search").build())).build();
            check(client.search("slow").items.size() == 1, "11 秒后返回响应头，超过旧 10 秒限制仍可成功");
        } finally {
            server.stop(0);
        }
    }

    private static Client fixture(int code, String body) {
        Client client = new Client(KEY);
        client.client = client.client.newBuilder().addInterceptor(chain -> response(chain.request(), code, body)).build();
        return client;
    }

    private static Response response(okhttp3.Request request, int code, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }

    private static IOException expectFailure(int code, String body, String... messages) throws Exception {
        try {
            fixture(code, body).search("test");
            throw new AssertionError("应报告 SerpApi 错误");
        } catch (IOException error) {
            for (String message : messages) check(error.getMessage().contains(message), "错误信息应包含 " + message);
            return error;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
