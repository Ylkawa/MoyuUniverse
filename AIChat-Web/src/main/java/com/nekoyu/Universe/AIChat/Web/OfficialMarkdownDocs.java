package com.nekoyu.Universe.AIChat.Web;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.regex.Pattern;

final class OfficialMarkdownDocs {
    private static final HttpUrl GEMINI_INDEX = HttpUrl.get("https://aistudio.google.com/llms.txt");
    private static final HttpUrl BIGMODEL_INDEX = HttpUrl.get("https://docs.bigmodel.cn/llms.txt");
    private static final HttpUrl MINIMAX_CN_INDEX = HttpUrl.get("https://platform.minimax.cn/docs/llms.txt");
    private static final HttpUrl MINIMAX_GLOBAL_INDEX = HttpUrl.get("https://platform.minimax.io/docs/llms.txt");
    private static final String SLUG = "/[A-Za-z0-9_-]+";
    private static final Pattern STUDIO_PATH = Pattern.compile("/(?:docs|api-reference)(?:" + SLUG + ")*");
    private static final Pattern LEGACY_PATH = Pattern.compile("/(?:gemini-api/docs|api)(?:" + SLUG + ")*");
    private static final Pattern BIGMODEL_PATH = Pattern.compile("/cn(?:/(?:[A-Za-z0-9_&-]|%26)+)+");
    private static final Pattern MINIMAX_PATH = Pattern.compile("/docs(?:" + SLUG + ")+");
    private static final Pattern ALIYUN_DOC = Pattern.compile("/(zh|en)(" + SLUG + ")(?:" + SLUG + ")+");
    private static final Pattern ALIYUN_LEGACY_DOC = Pattern.compile("/(zh|en)/(?:product|document_detail)/[0-9]+\\.html");
    private static final Pattern ALIYUN_INDEX = Pattern.compile("/(zh|en)(?:" + SLUG + "(?:\\.html)?)*?/llms\\.txt");
    private static final Pattern INDEX_LINK = Pattern.compile("\\]\\([^\\r\\n)]*(?:\\.md|llms\\.txt)(?:[?#][^\\r\\n)]*)?\\)");
    private static final Pattern HTML = Pattern.compile("(?i)^\\s*(?:<!doctype\\s+html\\b|<html\\b)");
    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_INDEX_BYTES = 4 * 1024 * 1024;
    private static final int INDEX_PAGE_CHARS = 64 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final String INDEX_PAGE = "aichat-index-page";

    private OfficialMarkdownDocs() {}

    private record Target(HttpUrl url, HttpUrl index, String scope) {
        boolean isIndex() {
            return url.encodedPath().endsWith("/llms.txt");
        }

        String indexLabel() {
            return scope.startsWith("aliyun-") ? "文档索引 " : "全站文档索引 ";
        }
    }

    /** Returns null for URLs outside the official Markdown routes. */
    static String visit(String url, OkHttpClient webClient) {
        HttpUrl input = HttpUrl.parse(url);
        Target document = input == null ? null : markdownTarget(input);
        if (document == null) return null;

        int page = 1;
        if (document.isIndex() && input.queryParameter(INDEX_PAGE) != null) {
            try {
                page = Integer.parseInt(input.queryParameter(INDEX_PAGE));
                if (page < 1) return failure(document, "索引页码必须是正整数");
            } catch (NumberFormatException error) {
                return failure(document, "索引页码必须是正整数");
            }
        }

        OkHttpClient client = webClient.newBuilder().followRedirects(false).build();
        try {
            return read(client, document, page);
        } catch (IOException error) {
            return failure(document, "请求失败（" + error.getClass().getSimpleName() + "）");
        }
    }

    private static Target markdownTarget(HttpUrl url) {
        if (url.port() != HttpUrl.defaultPort(url.scheme())
                || !url.username().isEmpty() || !url.password().isEmpty()) return null;

        String path = url.encodedPath();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.endsWith(".md")) path = path.substring(0, path.length() - 3);

        return switch (url.host()) {
            case "ai.google.dev" -> path.equals("/gemini-api/docs/pricing") ? null
                    : siteTarget(url, path, LEGACY_PATH, GEMINI_INDEX, "gemini", "/gemini-api/docs/llms.txt");
            case "aistudio.google.com" -> path.equals("/docs/pricing") ? null
                    : siteTarget(url, path, STUDIO_PATH, GEMINI_INDEX, "gemini", null);
            case "docs.bigmodel.cn" -> siteTarget(url, path, BIGMODEL_PATH, BIGMODEL_INDEX, "bigmodel-cn", null);
            case "platform.minimax.cn", "platform.minimaxi.com", "platform.minimax.io" -> {
                boolean global = url.host().equals("platform.minimax.io");
                String article = path.equals("/docs") ? "/docs/guides/models-intro" : path;
                yield siteTarget(url, article, MINIMAX_PATH, global ? MINIMAX_GLOBAL_INDEX : MINIMAX_CN_INDEX,
                        global ? "minimax-global" : "minimax-cn", null);
            }
            case "help.aliyun.com" -> aliyunTarget(url, path);
            default -> null;
        };
    }

    private static Target siteTarget(HttpUrl url, String path, Pattern pages, HttpUrl index, String scope, String indexAlias) {
        if (path.equals(index.encodedPath()) || path.equals(indexAlias)) return new Target(index, index, scope);
        if (!pages.matcher(path).matches()) return null;
        return new Target(normalized(url, path + ".md"), index, scope);
    }

    private static Target aliyunTarget(HttpUrl url, String path) {
        // The product indexes can resolve to a deeper document tree while keeping their language.
        if (path.equals("/llms.txt")) path = "/en/llms.txt";
        var index = ALIYUN_INDEX.matcher(path);
        if (index.matches()) {
            HttpUrl target = normalized(url, path).newBuilder().removeAllQueryParameters(INDEX_PAGE).build();
            return new Target(target, target, "aliyun-" + index.group(1));
        }

        var legacy = ALIYUN_LEGACY_DOC.matcher(path);
        if (legacy.matches()) {
            HttpUrl legacyIndex = normalized(url, path + "/llms.txt").newBuilder().query(null).build();
            return new Target(normalized(url, path + ".md"), legacyIndex, "aliyun-" + legacy.group(1));
        }

        var article = ALIYUN_DOC.matcher(path);
        if (!article.matches()) return null;
        String language = article.group(1);
        HttpUrl productIndex = normalized(url, "/" + language + article.group(2) + "/llms.txt")
                .newBuilder().query(null).build();
        return new Target(normalized(url, path + ".md"), productIndex, "aliyun-" + language);
    }

    private static HttpUrl normalized(HttpUrl url, String path) {
        return url.newBuilder().scheme("https").port(443).encodedPath(path).fragment(null).build();
    }

    private static String read(OkHttpClient client, Target target, int page) throws IOException {
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            HttpUrl url = target.url();
            Request request = new Request.Builder().url(url).header("Accept", "text/markdown, text/plain;q=0.9").build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    HttpUrl resolved = location == null ? null : url.resolve(location);
                    Target next = resolved == null ? null : markdownTarget(resolved);
                    if (next == null || !next.scope().equals(target.scope()) || next.isIndex() != target.isIndex()) {
                        return failure(target, "重定向目标不在同一官方文档范围内");
                    }
                    if (!next.isIndex() && next.url().encodedQuery() == null && url.encodedQuery() != null) {
                        next = new Target(next.url().newBuilder().encodedQuery(url.encodedQuery()).build(), next.index(), next.scope());
                    }
                    if (next.url().equals(url)) return failure(target, "文档重定向循环");
                    target = next;
                    continue;
                }
                if (!response.isSuccessful()) return failure(target, "HTTP " + response.code());
                ResponseBody body = response.body();
                if (body == null || !isMarkdown(body.contentType())) return failure(target, "服务器未返回 Markdown 或纯文本");
                boolean large = body.source().request(MAX_BYTES + 1L);
                if (large && !target.isIndex()) return failure(target, "文档超过 256 KiB 的单次读取上限");
                if (large && body.source().request(MAX_INDEX_BYTES + 1L)) return failure(target, "索引超过 4 MiB 的来源读取上限");
                String markdown = body.string();
                if (markdown.isBlank() || HTML.matcher(markdown).find()) return failure(target, "返回内容为空或为 HTML 页面");
                if (target.isIndex()) {
                    if (target.scope().startsWith("aliyun-") && !INDEX_LINK.matcher(markdown).find()) {
                        return failure(target, "官方索引未返回可用的文档链接");
                    }
                    if (large) return indexPage(target, markdown, page);
                    return page == 1 ? markdown : failure(target, "索引页码超出范围");
                }
                return "Markdown 来源 " + url + "\n" + target.indexLabel() + target.index() + "\n\n" + markdown;
            }
        }
        return failure(target, "文档重定向次数过多");
    }

    private static String indexPage(Target target, String text, int requestedPage) {
        int start = 0;
        for (int page = 1; start < text.length(); page++) {
            int end = Math.min(start + INDEX_PAGE_CHARS, text.length());
            if (end < text.length()) {
                end = text.lastIndexOf('\n', end - 1) + 1;
                if (end <= start) return failure(target, "索引中的单行超出分页上限");
            }
            if (page == requestedPage) {
                String next = end < text.length()
                        ? "\n下一页 " + target.url().newBuilder().setQueryParameter(INDEX_PAGE, Integer.toString(page + 1)).build()
                        : "";
                return "文档索引 " + target.url() + "\n第 " + page + " 页" + next + "\n\n" + text.substring(start, end);
            }
            start = end;
        }
        return failure(target, "索引页码超出范围");
    }

    private static boolean isMarkdown(MediaType type) {
        if (type == null || !type.type().equals("text")) return false;
        return switch (type.subtype()) {
            case "markdown", "x-markdown", "plain" -> true;
            default -> false;
        };
    }

    private static String failure(Target target, String reason) {
        String site = target.scope().equals("gemini") ? "Gemini " : "";
        return "未取得 " + site + "官方 Markdown（" + reason + "）。\n" + target.indexLabel() + target.index();
    }
}
