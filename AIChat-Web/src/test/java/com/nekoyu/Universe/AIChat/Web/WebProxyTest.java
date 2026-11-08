package com.nekoyu.Universe.AIChat.Web;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Verifies that dedicated page adapters use the Web plugin's injected HTTP client. */
public class WebProxyTest {
    private static final String VIDEO = "https://www.bilibili.com/video/BV17x411w7KC";
    private static final String WIKI_EXTRACT = """
            {"query":{"pages":{"1":{"title":"Proxy","extract":"Wiki extract fixture"}}}}
            """;

    public static void main(String[] args) throws Exception {
        testConfiguration();
        ProxySelector previous = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                throw new AssertionError("Request bypassed the configured client: " + uri.getHost() + uri.getPath());
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException error) {}
        });
        try {
            testPageRoutes();
        } finally {
            ProxySelector.setDefault(previous);
        }
        System.out.println("WebProxyTest passed");
    }

    private static void testConfiguration() throws Exception {
        Config config = new Config();
        config.HttpProxyURI = "not-a-proxy";
        check(Web.parseProxy(config) == Proxy.NO_PROXY, "disabled proxy ignores the address");
        config.EnableProxy = true;
        for (String address : new String[]{null, "", "proxy.example:8080", "http://proxy.example", "http://proxy.example:0",
                "http://proxy.example:65536", "http://proxy.example:8080:8081", "http://proxy.example:8080/path",
                "http://proxy.example:8080?q=x", "http://user:password@proxy.example:8080", "socks5://proxy.example:8080"}) {
            config.HttpProxyURI = address;
            try {
                Web.parseProxy(config);
                throw new AssertionError("invalid proxy accepted");
            } catch (IllegalArgumentException expected) {
                check(!expected.getMessage().contains("password"), "diagnostic omits supplied credentials");
            }
        }
        var web = new Web(null);
        check(!web.configureClients(config) && web.client == null && web.searchClient == null,
                "invalid enabled proxy does not initialize clients");

        config.HttpProxyURI = " http://[2001:db8::1]:8080/ ";
        var ipv6 = (InetSocketAddress) Web.parseProxy(config).address();
        check(ipv6.getHostString().equals("[2001:db8::1]") && ipv6.getPort() == 8080, "IPv6 proxy authority");
        config.HttpProxyURI = "http://proxy.example:8080";
        config.SearchProvider = "Google";
        check(web.configureClients(config) && web.client.followRedirects(), "configured clients retain API redirects");
        Field googleHttp = web.searchClient.getClass().getDeclaredField("client");
        googleHttp.setAccessible(true);
        var google = (OkHttpClient) googleHttp.get(web.searchClient);
        check(google.proxy().equals(Web.parseProxy(config)), "Google Custom Search uses the Web proxy");
    }

    private static void testPageRoutes() throws Exception {
        var web = new Web(null);
        Config config = new Config();
        config.EnableProxy = true;
        config.HttpProxyURI = "http://proxy.example:8080";
        check(web.configureClients(config), "valid proxy configuration");
        List<String> requests = new ArrayList<>();
        web.client = web.client.newBuilder()
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    String route = request.url().host() + request.url().encodedPath();
                    requests.add(route);
                    return switch (route) {
                        case "b23.tv/example" -> reply(request, 302, "").newBuilder()
                                .header("Location", VIDEO).build();
                        case "api.bilibili.com/x/web-interface/view" -> reply(request, 200, """
                                {"code":0,"data":{"title":"Bili video fixture","desc":"Video body",
                                "owner":{"name":"Fixture","mid":1},"stat":{},"argue_info":{"argue_msg":""},
                                "honor_reply":{}}}
                                """);
                        case "api.bilibili.com/x/v2/reply" -> reply(request, 200, "{\"data\":{\"replies\":[]}}");
                        case "api.bilibili.com/x/web-interface/nav" -> reply(request, 200, """
                                {"data":{"wbi_img":{
                                "img_url":"https://example.org/0123456789abcdef0123456789abcdef.png",
                                "sub_url":"https://example.org/fedcba9876543210fedcba9876543210.png"}}}
                                """);
                        case "api.bilibili.com/x/space/wbi/acc/info" -> {
                            check("123".equals(request.url().queryParameter("mid")), "space ID");
                            check(request.url().queryParameter("w_rid") != null, "profile request was signed");
                            yield reply(request, 200, "{\"data\":{\"name\":\"Bili profile fixture\",\"mid\":123,\"vip\":{}}}");
                        }
                        case "api.bilibili.com/x/polymer/web-dynamic/desktop/v1/feed/space" ->
                                reply(request, 200, "{\"data\":{\"items\":[]}}");
                        case "en.wikipedia.org/w/api.php", "zh.moegirl.org.cn/api.php" ->
                                reply(request, 200, WIKI_EXTRACT);
                        case "wiki.biligame.com/ys/api.php" -> reply(request, 200, """
                                {"query":{"pages":{"1":{"title":"Proxy","revisions":[{"*":"Wiki revision fixture"}]}}}}
                                """);
                        case "www.gamersky.com/handbook/202611/123.shtml" ->
                                reply(request, 200, "<div class='Mid2L_con'><p>Handbook fixture</p></div>");
                        case "bbs-api.miyoushe.com/post/wapi/getPostFull" -> reply(request, 200, """
                                {"data":{"post":{"post":{"subject":"Forum fixture","content":"<p>Forum body</p>"}}}}
                                """);
                        default -> throw new AssertionError("Unexpected adapter request: " + route);
                    };
                }).build();

        Method visit = Web.class.getDeclaredMethod("visitUrl", String.class);
        visit.setAccessible(true);
        check(visit.invoke(web, "https://b23.tv/example").toString().contains("Bili video fixture"), "short link video");
        check(visit.invoke(web, "https://space.bilibili.com/123").toString().contains("Bili profile fixture"), "Bili space");
        check(visit.invoke(web, "https://en.wikipedia.org/wiki/Proxy").toString().contains("Wiki extract fixture"), "Wikipedia");
        check(visit.invoke(web, "https://zh.moegirl.org.cn/Proxy").toString().contains("Wiki extract fixture"), "Moegirl");
        check(visit.invoke(web, "https://wiki.biligame.com/ys/Proxy").toString().contains("Wiki revision fixture"), "Biligame Wiki");
        check(visit.invoke(web, "https://www.gamersky.com/handbook/202611/123.shtml").toString().contains("Handbook fixture"), "GamerSky");
        check(visit.invoke(web, "https://www.miyoushe.com/ys/article/1234").toString().contains("Forum body"), "Miyoushe");

        check(requests.equals(List.of("b23.tv/example", "api.bilibili.com/x/web-interface/view", "api.bilibili.com/x/v2/reply",
                "api.bilibili.com/x/web-interface/nav", "api.bilibili.com/x/space/wbi/acc/info",
                "api.bilibili.com/x/polymer/web-dynamic/desktop/v1/feed/space", "en.wikipedia.org/w/api.php",
                "zh.moegirl.org.cn/api.php", "wiki.biligame.com/ys/api.php", "www.gamersky.com/handbook/202611/123.shtml",
                "bbs-api.miyoushe.com/post/wapi/getPostFull")), "each dedicated request uses the configured client");
    }

    private static Response reply(Request request, int status, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(ResponseBody.create(body, MediaType.get("text/plain; charset=utf-8"))).build();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
