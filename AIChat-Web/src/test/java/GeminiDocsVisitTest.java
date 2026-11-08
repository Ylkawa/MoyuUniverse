import com.nekoyu.Universe.AIChat.Web.Config;
import com.nekoyu.Universe.AIChat.Web.Web;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Exercises the Gemini documentation route with no browser or external requests. */
public class GeminiDocsVisitTest {
    private static final String STUDIO = "https://aistudio.google.com";
    private static final String LEGACY = "https://ai.google.dev/gemini-api/docs";
    private static final String INDEX_URL = STUDIO + "/llms.txt";
    private static final String DOCUMENT = """
            # Gemini models

            ## Features

            | Feature | Availability |
            | ------- | ------------ |
            | Tools   | Supported    |

            > **Note:** Check the [API guide](https://aistudio.google.com/docs/quickstart.md).

            ```python
            print("This is code, not a link: [demo](/docs/demo)")
            ```

            """ + "One line of technical reference text.\n".repeat(160) + """

            ## Further reading

            [Configuration](../configuration.md)
            [Model limits](https://aistudio.google.com/docs/models.md#limits)
            """;
    private static final String INDEX = """
            # Gemini API documentation

            ## Learn the API

            - [Models](https://aistudio.google.com/docs/models.md): Model features and limits.
            """ + "- Technical topic description and index metadata.\n".repeat(120) + """
            - [Quickstart](https://aistudio.google.com/docs/quickstart.md): Start using the API.
            """;

    public static void main(String[] args) throws Exception {
        var web = new Web(null);
        setField(web, "config", new Config());
        List<String> requested = new ArrayList<>();
        var proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080));
        setField(web, "client", new OkHttpClient.Builder().proxy(proxy).addInterceptor(chain -> {
            Request request = chain.request();
            check(request.url().isHttps(), "documentation fetch uses HTTPS");
            check(request.url().fragment() == null, "browser section anchors are not sent to the server");
            requested.add(request.url().toString());
            return switch (request.url().encodedPath()) {
                case "/docs.md", "/docs/models.md", "/gemini-api/docs/models.md", "/api-reference/models.md" ->
                        reply(request, 200, "text/markdown", DOCUMENT);
                case "/llms.txt" -> reply(request, 200, "text/plain", INDEX);
                case "/gemini-api/docs/redirected.md" -> reply(request, 302, "text/plain", "").newBuilder()
                        .header("Location", STUDIO + "/docs/models#limits").build();
                case "/docs/external-redirect.md" -> reply(request, 302, "text/plain", "").newBuilder()
                        .header("Location", "https://example.org/docs/models.md").build();
                case "/docs/missing-doc.md" ->
                        reply(request, 404, "text/html", "<html><body>not-found-page</body></html>");
                case "/docs/wrong-format.md" ->
                        reply(request, 200, "text/html", "<html><body>html-navigation-only</body></html>");
                case "/docs/oversize.md" -> reply(request, 200, "text/markdown",
                        "# Oversize document sentinel\n" + "a".repeat(256 * 1024));
                default -> throw new AssertionError("Unexpected documentation request " + request.url());
            };
        }).build());
        Method visit = Web.class.getDeclaredMethod("visitUrl", String.class);
        visit.setAccessible(true);

        for (String input : List.of(STUDIO + "/docs/", STUDIO + "/docs/models?generate-content=true#limits",
                LEGACY + "/models#limits", STUDIO + "/docs/models.md", LEGACY + "/redirected?hl=zh-cn",
                STUDIO + "/api-reference/models?api-version=v1")) {
            String result = visit.invoke(web, input).toString();
            check(result.contains(DOCUMENT.stripTrailing()), "preserve all Markdown, including sections beyond 5,000 characters");
            check(result.contains(INDEX_URL), "document exposes the full official index");
        }
        for (String input : List.of(INDEX_URL, LEGACY + "/llms.txt")) {
            String index = visit.invoke(web, input).toString();
            check(index.contains(INDEX.stripTrailing()), "retain the full official index, including late links");
        }

        String missing = visit.invoke(web, STUDIO + "/docs/missing-doc").toString();
        check(missing.contains("404") && !missing.contains("not-found-page"), "report missing Markdown without returning error HTML");
        String html = visit.invoke(web, STUDIO + "/docs/wrong-format").toString();
        check(!html.contains("html-navigation-only"), "reject HTML returned from a Markdown endpoint");
        String oversize = visit.invoke(web, STUDIO + "/docs/oversize").toString();
        check(!oversize.contains("Oversize document sentinel") && oversize.contains("256"),
                "fail explicitly rather than returning a truncated Markdown body");
        String external = visit.invoke(web, STUDIO + "/docs/external-redirect").toString();
        check(!external.contains(DOCUMENT.stripTrailing()), "reject redirects to nonofficial URLs");

        check(requested.equals(List.of(STUDIO + "/docs.md", STUDIO + "/docs/models.md?generate-content=true",
                LEGACY + "/models.md", STUDIO + "/docs/models.md", LEGACY + "/redirected.md?hl=zh-cn",
                STUDIO + "/docs/models.md?hl=zh-cn", STUDIO + "/api-reference/models.md?api-version=v1", INDEX_URL, INDEX_URL,
                STUDIO + "/docs/missing-doc.md", STUDIO + "/docs/wrong-format.md", STUDIO + "/docs/oversize.md",
                STUDIO + "/docs/external-redirect.md")), "exact Markdown and index routes, with query and redirect handling");

        int requestCount = requested.size();
        for (String unsupported : List.of(STUDIO + "/apps/123", STUDIO + "/docs-preview/models",
                STUDIO + "/docs/pricing", LEGACY + "/pricing.md",
                "https://aistudio.google.com.example.org/docs/models", "https://ai.google.dev/other/models")) {
            check(visit.invoke(web, unsupported).toString().contains("不支持的链接类型"), "non-docs URL uses the generic route");
        }
        check(requested.size() == requestCount, "non-docs URLs are not fetched by the documentation route");
        System.out.println("GeminiDocsVisitTest passed");
    }

    private static Response reply(Request request, int code, String mediaType, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(ResponseBody.create(body, MediaType.get(mediaType + "; charset=utf-8"))).build();
    }

    private static void setField(Web web, String name, Object value) throws Exception {
        Field field = Web.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(web, value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
