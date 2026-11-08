package com.nekoyu.Universe.AIChat.Web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class GitHub {
    private static final HttpUrl API = HttpUrl.get("https://api.github.com/repos/");
    private static final String JSON = "application/vnd.github+json";
    private static final String RAW = "application/vnd.github.raw+json";
    private static final int BODY_LIMIT = 5000;
    private static final Set<String> SITE_SECTIONS = Set.of(
            "about", "apps", "collections", "enterprise", "features", "marketplace", "orgs",
            "pricing", "search", "security", "settings", "sponsors", "topics", "trending", "users");

    private GitHub() {}

    /** Returns null for page types that should continue through the generic fetcher. */
    static String visit(String url, OkHttpClient webClient) {
        HttpUrl page = HttpUrl.parse(url);
        if (page == null) return null;
        List<String> path = page.pathSegments();
        if (path.get(path.size() - 1).isEmpty()) path = path.subList(0, path.size() - 1);
        if (path.size() < 2 || !path.get(0).matches("[A-Za-z0-9][A-Za-z0-9-]*")
                || !path.get(1).matches("[A-Za-z0-9_.-]+")
                || SITE_SECTIONS.contains(path.get(0).toLowerCase(Locale.ROOT))) return null;

        HttpUrl repo = API.newBuilder().addPathSegment(path.get(0)).addPathSegment(path.get(1)).build();
        OkHttpClient client = webClient.newBuilder().followRedirects(true).followSslRedirects(false).build();
        try {
            if (path.size() == 2) return repository(client, repo);
            if (path.size() == 4 && path.get(2).equals("issues") && path.get(3).matches("[1-9][0-9]*")
                    && (page.fragment() == null || page.fragment().equals("issue-" + path.get(3)))) {
                HttpUrl issue = repo.newBuilder().addPathSegment("issues").addPathSegment(path.get(3)).build();
                JsonObject data = object(client, issue);
                return "# Issue #" + path.get(3) + " " + text(data, "title") + "\n"
                        + text(data, "html_url") + "\n状态 " + text(data, "state") + "\n\n"
                        + excerpt(text(data, "body"), BODY_LIMIT);
            }
            if (path.get(2).equals("releases")) {
                HttpUrl.Builder releases = repo.newBuilder().addPathSegment("releases");
                if (path.size() == 3 && page.queryParameterNames().stream()
                        .noneMatch(Set.of("page", "before", "after", "q")::contains)) {
                    return releaseList(client, releases.addQueryParameter("per_page", "3").build());
                }
                if (path.size() == 4 && path.get(3).equals("latest")) {
                    return release(object(client, releases.addPathSegment("latest").build()), BODY_LIMIT);
                }
                if (path.size() >= 5 && path.get(3).equals("tag")) {
                    String tag = String.join("/", path.subList(4, path.size()));
                    HttpUrl endpoint = releases.addPathSegment("tags").addPathSegment(tag).build();
                    return release(object(client, endpoint), BODY_LIMIT);
                }
            }
            return null;
        } catch (IOException | JsonParseException | IllegalStateException error) {
            return failure(error);
        }
    }

    private static String repository(OkHttpClient client, HttpUrl repo) throws IOException {
        JsonObject data = object(client, repo);
        String header = "# " + text(data, "full_name") + "\n" + text(data, "html_url")
                + "\n" + text(data, "description") + "\n默认分支 " + text(data, "default_branch");
        try {
            String readme = get(client, repo.newBuilder().addPathSegment("readme").build(), RAW);
            return header + "\n\n## README\n" + excerpt(readme, BODY_LIMIT);
        } catch (IOException error) {
            return header + "\n\nREADME 未取得。" + failure(error);
        }
    }

    private static String releaseList(OkHttpClient client, HttpUrl url) throws IOException {
        var releases = JsonParser.parseString(get(client, url, JSON)).getAsJsonArray();
        if (releases.isEmpty()) return "GitHub 仓库暂无公开 Release。";
        StringBuilder result = new StringBuilder("# GitHub Releases（最多显示 3 条，单条链接可读详情）");
        for (var item : releases) result.append("\n\n").append(release(item.getAsJsonObject(), 1000));
        return result.toString();
    }

    private static String release(JsonObject data, int limit) {
        String name = text(data, "name");
        String tag = text(data, "tag_name");
        return "## " + (name.isBlank() ? tag : name) + "\n" + text(data, "html_url")
                + "\n标签 " + tag + ("true".equals(text(data, "prerelease")) ? "（预发布）" : "")
                + " · 发布于 " + text(data, "published_at") + "\n\n" + excerpt(text(data, "body"), limit);
    }

    private static JsonObject object(OkHttpClient client, HttpUrl url) throws IOException {
        return JsonParser.parseString(get(client, url, JSON)).getAsJsonObject();
    }

    private static String get(OkHttpClient client, HttpUrl url, String accept) throws IOException {
        Request request = new Request.Builder().url(url).header("Accept", accept)
                .header("User-Agent", "MoyuUniverse-AIChat-Web").header("X-GitHub-Api-Version", "2026-03-10").build();
        try (Response response = client.newCall(request).execute()) {
            int status = response.code();
            if (status == 404) throw new ApiError("GitHub API 未找到公开资源（HTTP 404）。");
            if (status == 429 || (status == 403 && ("0".equals(response.header("X-RateLimit-Remaining"))
                    || response.header("Retry-After") != null))) {
                throw new ApiError("GitHub API 请求受限（HTTP " + status + "），请稍后再试。");
            }
            if (!response.isSuccessful() || response.body() == null) {
                throw new ApiError("GitHub API 访问失败（HTTP " + status + "）。");
            }
            return RAW.equals(accept) ? response.peekBody(4L * BODY_LIMIT + 4).string() : response.body().string();
        }
    }

    private static String text(JsonObject data, String key) {
        var value = data.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static String excerpt(String body, int limit) {
        if (body.isBlank()) return "（正文为空）";
        if (body.length() <= limit) return body;
        int end = Character.isHighSurrogate(body.charAt(limit - 1)) ? limit - 1 : limit;
        return body.substring(0, end) + "\n\n（正文超过 " + limit + " 字符，已截断。）";
    }

    private static String failure(Exception error) {
        return error instanceof ApiError ? error.getMessage()
                : "GitHub API 未取得内容（" + error.getClass().getSimpleName() + "）。";
    }

    private static final class ApiError extends IOException {
        private ApiError(String message) { super(message); }
    }
}
