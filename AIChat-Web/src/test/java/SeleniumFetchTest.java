import com.google.gson.*;
import com.nekoyu.Universe.AIChat.Web.Config;
import com.nekoyu.Universe.AIChat.Web.Web;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 使用本地 W3C WebDriver 服务验证真实 Selenium 客户端的错误处理和会话释放，无需浏览器。 */
public class SeleniumFetchTest {
    static final String PAGE = "https://example.org/article";
    static final Gson GSON = new Gson();

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static String visit(String endpoint) throws Exception {
        Web web = new Web(null);
        JsonObject config = new JsonObject();
        if (endpoint != null) {
            JsonObject selenium = new JsonObject();
            selenium.addProperty("URL", endpoint);
            config.add("Selenium", selenium);
        }
        Field field = Web.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(web, GSON.fromJson(config, Config.class));
        Method method = Web.class.getDeclaredMethod("visitUrl", String.class);
        method.setAccessible(true);
        return method.invoke(web, PAGE).toString();
    }

    public static void main(String[] args) throws Exception {
        check(visit(null).contains("不支持的链接类型"), "disabled Selenium preserves refusal");
        for (String endpoint : new String[]{"", "invalid", "file:///tmp/grid", "http:///grid"})
            check(visit(endpoint).contains("Selenium 配置错误"), "invalid endpoint: " + endpoint);

        try (Grid grid = new Grid("success")) {
            String result = visit(grid.endpoint());
            check(result.contains("离线测试正文"), "body returned");
            check(grid.polls.get() == 2, "waits for rendered body");
            check(grid.deletes.get() == 1, "successful fetch quits session");
            grid.checkCapabilities();
        }
        try (Grid grid = new Grid("long")) {
            check(visit(grid.endpoint()).length() == 5000, "existing text limit preserved");
            check(grid.deletes.get() == 1, "truncated result quits session");
        }
        for (String mode : new String[]{"navigation-timeout", "script-error", "source-error"}) {
            try (Grid grid = new Grid(mode)) {
                String result = visit(grid.endpoint());
                check(result.contains(mode.equals("navigation-timeout") ? "超时：加载页面" : "抓取失败"), mode);
                check(!result.contains("离线测试正文"), "failed fetch does not return body: " + mode);
                check(grid.deletes.get() == 1, "failed fetch quits session: " + mode);
            }
        }
        try (Grid grid = new Grid("quit-error")) {
            check(visit(grid.endpoint()).contains("离线测试正文"), "cleanup failure preserves result");
            check(grid.deletes.get() == 1, "cleanup attempted once");
        }
        try (Grid grid = new Grid("session-error")) {
            check(visit(grid.endpoint()).contains("连接服务并创建浏览器会话"), "session failure is actionable");
            check(grid.deletes.get() == 0, "no session created to delete");
        }
        System.out.println("SeleniumFetchTest passed");
    }

    static class Grid implements AutoCloseable {
        final HttpServer server;
        final String mode;
        final AtomicInteger polls = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();
        volatile JsonObject newSession;

        Grid(String mode) throws IOException {
            this.mode = mode;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        void checkCapabilities() {
            JsonObject capabilities = newSession.getAsJsonObject("capabilities");
            JsonObject first = capabilities.getAsJsonArray("firstMatch").get(0).getAsJsonObject();
            JsonObject always = capabilities.getAsJsonObject("alwaysMatch");
            JsonObject combined = new JsonObject();
            if (always != null) always.entrySet().forEach(e -> combined.add(e.getKey(), e.getValue()));
            first.entrySet().forEach(e -> combined.add(e.getKey(), e.getValue()));
            check(combined.get("pageLoadStrategy").getAsString().equals("eager"), "DOM navigation strategy");
            check(combined.getAsJsonObject("timeouts").get("pageLoad").getAsInt() == 30_000,
                    "page navigation bounded before get");
            check(combined.getAsJsonObject("timeouts").get("script").getAsInt() == 10_000,
                    "script timeout configured");
        }

        void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Object value = null;
            int status = 200;
            if (path.equals("/session") && method.equals("POST")) {
                newSession = JsonParser.parseString(body).getAsJsonObject();
                if (mode.equals("session-error")) {
                    status = 500;
                    value = error("session not created", "offline browser unavailable");
                } else value = Map.of("sessionId", "offline", "capabilities", Map.of("browserName", "chrome"));
            } else if (path.equals("/session/offline") && method.equals("DELETE")) {
                deletes.incrementAndGet();
                if (mode.equals("quit-error")) {
                    status = 404;
                    value = error("invalid session id", "offline cleanup failure");
                }
            } else if (path.endsWith("/url")) {
                if (mode.equals("navigation-timeout")) {
                    status = 500;
                    value = error("timeout", "offline navigation timed out");
                }
            } else if (path.endsWith("/source")
                    || (path.endsWith("/execute/sync") && body.contains("outerHTML"))) {
                if (mode.equals("source-error")) {
                    status = 500;
                    value = error("unknown error", "offline source failure");
                } else value = "<html><body>" + (mode.equals("long") ? "字".repeat(5100) : "离线测试正文")
                        + "</body></html>";
            } else if (path.endsWith("/execute/sync")) {
                if (mode.equals("script-error")) {
                    status = 500;
                    value = error("javascript error", "offline script failure");
                } else value = polls.incrementAndGet() > 1;
            } else {
                status = 404;
                value = error("unknown command", path);
            }
            JsonObject response = new JsonObject();
            response.add("value", GSON.toJsonTree(value));
            byte[] bytes = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        static Map<String, String> error(String error, String message) {
            return Map.of("error", error, "message", message, "stacktrace", "");
        }

        public void close() { server.stop(0); }
    }
}
