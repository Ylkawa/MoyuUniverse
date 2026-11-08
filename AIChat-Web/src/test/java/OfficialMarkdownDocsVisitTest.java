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
import java.util.ArrayList;
import java.util.List;

/** Checks documented routes and that returning long index pages loses no lines. */
public class OfficialMarkdownDocsVisitTest {
    private static final String BIGMODEL = "https://docs.bigmodel.cn";
    private static final String MINIMAX_CN = "https://platform.minimax.cn";
    private static final String MINIMAX_ALIAS = "https://platform.minimaxi.com";
    private static final String MINIMAX_GLOBAL = "https://platform.minimax.io";
    private static final String ALIYUN = "https://help.aliyun.com";
    private static final String PRODUCT_INDEX = ALIYUN + "/zh/model-studio/llms.txt";
    private static final String RESOLVED_INDEX = ALIYUN + "/zh/model-studio/what-is-model-studio/llms.txt";
    private static final String DOCUMENT = """
            # Development guide

            | Field | Value |
            | --- | --- |
            | Mode | Streaming |

            ```json
            {"stream": true}
            ```

            """ + "Paragraph from official developer documentation.\n".repeat(150) + """

            ## Related links

            [Next chapter](../next.md#example)
            """;
    private static final String SMALL_INDEX = "# 文档目录\n- [相关指南](guide.md)\n";
    private static final String LONG_INDEX = longIndex();

    public static void main(String[] args) throws Exception {
        Web web = new Web(null);
        setField(web, "config", new Config());
        List<String> requests = new ArrayList<>();
        setField(web, "client", new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            check(request.url().fragment() == null, "anchors stay out of requests");
            check(request.url().queryParameter("aichat-index-page") == null, "continuation is consumed by the adapter");
            requests.add(request.url().toString());
            return switch (request.url().host() + request.url().encodedPath()) {
                case "docs.bigmodel.cn/cn/guide/tools/knowledge/q&a.md",
                     "platform.minimax.cn/docs/guides/models-intro.md",
                     "platform.minimax.io/docs/guides/models-intro.md",
                     "help.aliyun.com/zh/model-studio/text-generation-model.md", "help.aliyun.com/zh/airec/airec.md" ->
                        reply(request, "text/markdown", DOCUMENT);
                case "docs.bigmodel.cn/llms.txt", "platform.minimax.cn/docs/llms.txt",
                     "platform.minimax.io/docs/llms.txt", "help.aliyun.com/en/llms.txt",
                     "help.aliyun.com/zh/product/435019.html/llms.txt" -> reply(request, "text/plain", SMALL_INDEX);
                case "help.aliyun.com/zh/document_detail/2590669.html.md" -> redirect(request, ALIYUN + "/zh/airec/airec.md");
                case "help.aliyun.com/zh/document_detail/2590669.html/llms.txt" -> redirect(request, ALIYUN + "/zh/airec/airec/llms.txt");
                case "help.aliyun.com/zh/airec/airec/llms.txt" -> reply(request, "text/plain", "Index unavailable");
                case "platform.minimaxi.com/docs/guides/models-intro.md" -> redirect(request,
                        MINIMAX_CN + "/docs/guides/models-intro");
                case "help.aliyun.com/zh/model-studio/llms.txt" -> redirect(request, RESOLVED_INDEX);
                case "help.aliyun.com/zh/model-studio/what-is-model-studio/llms.txt" -> reply(request, "text/plain", LONG_INDEX);
                case "platform.minimax.cn/docs/guides/other-region.md" -> redirect(request,
                        MINIMAX_GLOBAL + "/docs/guides/models-intro");
                case "help.aliyun.com/zh/model-studio/other-language.md" -> redirect(request,
                        ALIYUN + "/en/model-studio/text-generation-model");
                default -> throw new AssertionError("Unexpected documentation request " + request.url());
            };
        }).build());
        Method visit = Web.class.getDeclaredMethod("visitUrl", String.class);
        visit.setAccessible(true);

        checkDocument(visit, web, BIGMODEL + "/cn/guide/tools/knowledge/q&a#example", BIGMODEL + "/llms.txt");
        checkDocument(visit, web, MINIMAX_ALIAS + "/docs/guides/models-intro?example=true", MINIMAX_CN + "/docs/llms.txt");
        check(requests.contains(MINIMAX_CN + "/docs/guides/models-intro.md?example=true"), "preserve query across alias redirects");
        checkDocument(visit, web, MINIMAX_GLOBAL + "/docs/guides/models-intro.md", MINIMAX_GLOBAL + "/docs/llms.txt");
        checkDocument(visit, web, MINIMAX_CN + "/docs", MINIMAX_CN + "/docs/llms.txt");
        checkDocument(visit, web, ALIYUN + "/zh/model-studio/text-generation-model/", PRODUCT_INDEX);
        checkDocument(visit, web, ALIYUN + "/zh/document_detail/2590669.html", ALIYUN + "/zh/airec/llms.txt");

        for (String index : List.of(BIGMODEL + "/llms.txt", MINIMAX_ALIAS + "/docs/llms.txt", MINIMAX_GLOBAL + "/docs/llms.txt",
                ALIYUN + "/llms.txt", ALIYUN + "/zh/product/435019.html/llms.txt")) {
            check(visit.invoke(web, index).toString().equals(SMALL_INDEX), "preserve the index: " + index);
        }
        String unavailable = visit.invoke(web, ALIYUN + "/zh/document_detail/2590669.html/llms.txt").toString();
        check(unavailable.contains("未返回可用的文档链接") && !unavailable.contains("Index unavailable"), "reject text error from the index endpoint");

        String pageUrl = PRODUCT_INDEX;
        StringBuilder restoredIndex = new StringBuilder();
        int pageNumber = 1;
        while (pageUrl != null) {
            String page = visit.invoke(web, pageUrl).toString();
            int boundary = page.indexOf("\n\n");
            check(boundary > 0, "split index page header from raw text");
            String header = page.substring(0, boundary);
            check(header.contains("第 " + pageNumber++ + " 页"), "index page number");
            restoredIndex.append(page.substring(boundary + 2));
            pageUrl = header.lines().filter(line -> line.startsWith("下一页 ")).findFirst()
                    .map(line -> line.substring("下一页 ".length())).orElse(null);
            check(pageNumber < 20, "index pagination terminates");
        }
        check(pageNumber > 2 && restoredIndex.toString().equals(LONG_INDEX), "all index lines and links survive pagination");

        for (String rejected : List.of(MINIMAX_CN + "/docs/guides/other-region", ALIYUN + "/zh/model-studio/other-language")) {
            check(visit.invoke(web, rejected).toString().contains("重定向"), "don't cross regions or languages");
        }
        for (String unsupported : List.of(MINIMAX_CN + "/console", BIGMODEL + "/en/guide/start/quick-start", ALIYUN + "/product/ecs")) {
            check(visit.invoke(web, unsupported).toString().contains("不支持的链接类型"), "outside documented routes");
        }
        System.out.println("OfficialMarkdownDocsVisitTest passed");
    }

    private static void checkDocument(Method visit, Web web, String url, String index) throws Exception {
        String result = visit.invoke(web, url).toString();
        check(result.contains(index) && result.endsWith(DOCUMENT), "preserve Markdown and the matching index: " + url);
    }

    private static String longIndex() {
        StringBuilder index = new StringBuilder("# 百炼文档目录\n");
        for (int i = 0; i < 3500; i++) {
            index.append("- [模型开发与接入指南 ").append(i).append("](https://help.aliyun.com/zh/model-studio/topic-")
                    .append(i).append(".md) 描述、示例和参考资料\n");
        }
        return index.toString();
    }

    private static Response redirect(Request request, String url) {
        return reply(request, "text/plain", "").newBuilder().code(302).header("Location", url).build();
    }

    private static Response reply(Request request, String type, String text) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(ResponseBody.create(text, MediaType.get(type + "; charset=utf-8"))).build();
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
