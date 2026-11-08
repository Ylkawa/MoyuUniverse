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

/** Drives the existing page router without Selenium or live GitHub requests. */
public class GitHubVisitTest {
    private static final String REPO = "https://github.com/sample/project";
    private static final String API_PATH = "/repos/sample/project";
    private static final String RELEASE = """
            {"tag_name":"beta/v2","name":"Beta 2","prerelease":true,"published_at":"2026-10-25T00:00:00Z",
             "html_url":"https://github.com/sample/project/releases/tag/beta/v2","body":"Release notes"}
            """;

    public static void main(String[] args) throws Exception {
        List<String> requests = new ArrayList<>();
        var web = new Web(null);
        setField(web, "config", new Config());
        var proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080));
        setField(web, "client", new OkHttpClient.Builder().proxy(proxy).followRedirects(false)
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    check(request.url().isHttps() && request.url().host().equals("api.github.com"), "fixed API origin");
                    check(chain.call().request().header("User-Agent") != null, "API user agent");
                    check(request.header("Authorization") == null, "anonymous public API");
                    String path = request.url().encodedPath();
                    requests.add(path);
                    return switch (path) {
                        case API_PATH -> reply(request, 200, """
                                {"full_name":"sample/project","description":"Repository description","default_branch":"main"}
                                """);
                        case API_PATH + "/readme" -> {
                            check("application/vnd.github.raw+json".equals(request.header("Accept")), "raw README requested");
                            yield reply(request, 200, "# README text\n" + "a".repeat(5100));
                        }
                        case API_PATH + "/issues/42" -> reply(request, 200, """
                                {"title":"Issue title","state":"closed","body":"Issue body"}
                                """);
                        case API_PATH + "/issues/43" -> reply(request, 404, "{\"message\":\"Not Found\"}");
                        case API_PATH + "/issues/44" -> reply(request, 403, "{}").newBuilder()
                                .header("X-RateLimit-Remaining", "0").build();
                        case API_PATH + "/releases" -> {
                            check("3".equals(request.url().queryParameter("per_page")), "bounded release list");
                            yield reply(request, 200, "[" + RELEASE + "]");
                        }
                        case API_PATH + "/releases/latest", API_PATH + "/releases/tags/beta%2Fv2" ->
                                reply(request, 200, RELEASE);
                        default -> throw new AssertionError("Unexpected GitHub API path " + path);
                    };
                }).build());
        Method visit = Web.class.getDeclaredMethod("visitUrl", String.class);
        visit.setAccessible(true);

        String repo = visit.invoke(web, REPO + "/?utm_source=example").toString();
        check(repo.contains("Repository description") && repo.contains("# README text") && repo.contains("已截断"),
                "repository summary and bounded README without Selenium");
        String issue = visit.invoke(web, REPO + "/issues/42#issue-42").toString();
        check(issue.contains("Issue title") && issue.contains("closed") && issue.contains("Issue body"), "Issue body");
        check(visit.invoke(web, REPO + "/issues/43").toString().contains("HTTP 404"), "missing resource reported");
        check(visit.invoke(web, REPO + "/issues/44").toString().contains("请求受限"), "rate limit reported");
        check(visit.invoke(web, REPO + "/releases").toString().contains("预发布"), "release list marks prereleases");
        check(visit.invoke(web, REPO + "/releases/latest").toString().contains("Release notes"), "latest release body");
        check(visit.invoke(web, REPO + "/releases/tag/beta/v2").toString().contains("Release notes"), "tag with slash");

        for (String url : List.of(REPO + "/blob/main/README.md", REPO + "/issues/42#issuecomment-123",
                REPO + "/releases?page=2", "https://github.com/topics/java", "https://github.com.example.org/sample/project")) {
            check(visit.invoke(web, url).toString().contains("不支持的链接类型"), "unmatched page follows generic route");
        }
        check(requests.equals(List.of(API_PATH, API_PATH + "/readme", API_PATH + "/issues/42", API_PATH + "/issues/43",
                API_PATH + "/issues/44", API_PATH + "/releases", API_PATH + "/releases/latest",
                API_PATH + "/releases/tags/beta%2Fv2")), "exact supported paths");
        System.out.println("GitHubVisitTest passed");
    }

    private static Response reply(Request request, int code, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(ResponseBody.create(body, MediaType.get("text/plain; charset=utf-8"))).build();
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
