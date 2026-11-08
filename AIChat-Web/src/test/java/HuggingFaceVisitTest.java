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

/** Covers the public Hub routes without contacting Hugging Face or launching Selenium. */
public class HuggingFaceVisitTest {
    private static final String HUB = "https://huggingface.co";
    private static final String SHA = "1234567890abcdef1234567890abcdef12345678";
    private static final String CARD = """
            ---
            license: apache-2.0
            ---
            # Model card

            """ + "Documentation paragraph.\n".repeat(250) + """

            ## Evaluation

            | Task | Score |
            | --- | --- |
            | Example | 0.91 |

            ```python
            print("complete")
            ```

            [Further reading](./docs/evaluation.md)
            """;

    public static void main(String[] args) throws Exception {
        List<String> requests = new ArrayList<>();
        Web web = new Web(null);
        setField(web, "config", new Config());
        var proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080));
        setField(web, "client", new OkHttpClient.Builder().proxy(proxy).addInterceptor(chain -> {
            Request request = chain.request();
            check(request.url().isHttps() && request.url().host().equals("huggingface.co"), "fixed official origin");
            check(request.header("Authorization") == null, "public requests without credentials");
            String path = request.url().encodedPath();
            requests.add(path);
            return switch (path) {
                case "/api/organizations/sample/overview" -> reply(request, 200, "application/json", """
                        {"fullname":"Sample organization","details":"Public research","numModels":12}
                        """);
                case "/spaces/sample/README/raw/main/README.md" -> reply(request, 200, "text/plain",
                        "## Organization card\n\n[Research](https://example.org/research)");
                case "/api/collections" -> {
                    check("sample".equals(request.url().queryParameter("owner")), "collections use the requested owner");
                    check("10".equals(request.url().queryParameter("limit")), "bounded collection query");
                    yield reply(request, 200, "application/json", "[{\"slug\":\"sample/series-123\",\"title\":\"Series\"}]");
                }
                case "/api/models" -> {
                    check("sample".equals(request.url().queryParameter("author")), "models use the requested author");
                    check("10".equals(request.url().queryParameter("limit")), "bounded model query");
                    yield reply(request, 200, "application/json", "[" + String.join(",",
                            java.util.stream.IntStream.rangeClosed(1, 11)
                                    .mapToObj(i -> "{\"id\":\"sample/model-" + i + "\",\"pipeline_tag\":\"text-generation\"}").toList()) + "]");
                }
                case "/api/models/sample/example" -> reply(request, 200, "application/json", """
                        {"id":"sample/example","sha":"%s","pipeline_tag":"text-generation",
                         "library_name":"transformers","cardData":{"license":"apache-2.0","license_link":"LICENSE"}}
                        """.formatted(SHA));
                case "/sample/example/raw/" + SHA + "/README.md" -> reply(request, 200, "text/plain", CARD);
                case "/api/models/sample/no-card", "/api/models/sample/gated" ->
                        reply(request, 200, "application/json", "{\"tags\":[\"license:mit\"]}");
                case "/sample/no-card/raw/main/README.md" -> reply(request, 404, "text/plain", "not found");
                case "/sample/gated/raw/main/README.md" -> reply(request, 403, "text/plain", "access denied");
                case "/api/models/sample/wrong-shape" -> reply(request, 200, "application/json", "[]");
                case "/api/models/sample/moved" -> reply(request, 302, "text/plain", "").newBuilder()
                        .header("Location", "https://example.org/unexpected").build();
                case "/api/organizations/user/overview" -> reply(request, 404, "application/json", "{}");
                default -> throw new AssertionError("Unexpected Hub request " + request.url());
            };
        }).build());
        Method visit = Web.class.getDeclaredMethod("visitUrl", String.class);
        visit.setAccessible(true);

        String org = visit.invoke(web, HUB + "/sample/").toString();
        check(org.contains("Sample organization") && org.contains("Public research"), "organization overview");
        check(org.contains("https://example.org/research"), "organization card links survive");
        check(org.contains(HUB + "/collections/sample/series-123") && org.contains(HUB + "/sample/collections"),
                "collection detail and complete collection list links");
        check(org.contains(HUB + "/sample/model-10") && !org.contains("sample/model-11") && org.contains(HUB + "/sample/models"),
                "model list is bounded and exposes the full listing");
        String model = visit.invoke(web, HUB + "/sample/example").toString();
        check(model.contains("apache-2.0") && model.contains(HUB + "/sample/example/blob/" + SHA + "/LICENSE"), "license and link");
        check(model.contains(HUB + "/sample/example/tree/" + SHA) && model.contains(CARD.stripTrailing()),
                "pinned revision and full model card, including late links and examples");
        String noCard = visit.invoke(web, HUB + "/sample/no-card").toString();
        check(noCard.contains("未找到 README.md") && noCard.contains("许可证 mit"), "missing card retains metadata");
        check(visit.invoke(web, HUB + "/sample/gated").toString().contains("HTTP 403"), "card denial is distinct from absence");
        check(visit.invoke(web, HUB + "/sample/wrong-shape").toString().contains("数据格式不正确"), "malformed shape reported");
        check(visit.invoke(web, HUB + "/sample/moved").toString().contains("重定向目标无效"), "off-origin redirect rejected");
        check(visit.invoke(web, HUB + "/user").toString().contains("不支持的链接类型"), "personal profile retains generic route");
        int count = requests.size();
        for (String url : List.of(HUB + "/datasets/sample", HUB + "/sample/models", HUB + "/docs/hub", HUB + "/sample/example/tree/main")) {
            check(visit.invoke(web, url).toString().contains("不支持的链接类型"), "out-of-scope Hub page retains generic route");
        }
        check(requests.size() == count, "unmatched routes do not make API requests");
        System.out.println("HuggingFaceVisitTest passed");
    }

    private static Response reply(Request request, int status, String mediaType, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
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
