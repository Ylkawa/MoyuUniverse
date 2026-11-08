package com.nekoyu.Universe.AIChat.Web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class HuggingFace {
    private static final HttpUrl HUB = HttpUrl.get("https://huggingface.co/");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*");
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{40}");
    private static final Pattern HTML_PAGE = Pattern.compile("(?i)^\\s*(?:<!doctype\\s+html\\b|<html\\b)");
    private static final Set<String> SITE_SECTIONS = Set.of("about", "api", "blog", "collections", "datasets", "docs",
            "enterprise", "join", "login", "models", "organizations", "papers", "pricing", "settings", "spaces", "tasks");
    private static final Set<String> PROFILE_SECTIONS = Set.of("activity", "collections", "datasets", "followers",
            "following", "models", "papers", "posts", "spaces");
    private static final int LIST_LIMIT = 10;
    private static final int CARD_LIMIT = 256 * 1024;
    private static final int API_LIMIT = 1024 * 1024;
    private static final String JSON = "application/json";
    private static final String MARKDOWN = "text/markdown, text/plain, application/octet-stream;q=0.8";

    private HuggingFace() {}

    /** Returns null for pages outside the organization and model routes. */
    static String visit(String url, OkHttpClient webClient) {
        HttpUrl page = HttpUrl.parse(url);
        if (page == null || !page.host().equals(HUB.host()) || page.port() != HttpUrl.defaultPort(page.scheme())
                || !page.username().isEmpty() || !page.password().isEmpty()) return null;
        List<String> path = page.pathSegments();
        if (path.get(path.size() - 1).isEmpty()) path = path.subList(0, path.size() - 1);
        if (path.isEmpty() || path.size() > 2 || !NAME.matcher(path.get(0)).matches() || SITE_SECTIONS.contains(path.get(0))) {
            return null;
        }
        String owner = path.get(0);
        OkHttpClient client = webClient.newBuilder().followRedirects(false).build();
        try {
            if (path.size() == 1) return organization(client, owner);
            String repo = path.get(1);
            if (!NAME.matcher(repo).matches() || PROFILE_SECTIONS.contains(repo)) return null;
            return model(client, owner, repo);
        } catch (IOException | IllegalStateException error) {
            return "Hugging Face 未取得公开页面内容（" + reason(error) + "）。";
        }
    }

    private static String organization(OkHttpClient client, String owner) throws IOException {
        HttpUrl overview = HUB.newBuilder().addPathSegments("api/organizations").addPathSegment(owner)
                .addPathSegment("overview").build();
        String rawOverview = fetch(client, overview, JSON, API_LIMIT, true);
        if (rawOverview == null) return null; // Personal profiles share the same URL shape.
        JsonObject org = parse(rawOverview).getAsJsonObject();
        String name = field(org, "fullname");
        if (name.isBlank()) name = owner;
        StringBuilder out = new StringBuilder("# ").append(name).append("\n组织主页 ").append(link(owner));
        appendField(out, "简介", field(org, "details"));
        appendField(out, "模型数", field(org, "numModels"));
        out.append("\n数据集 ").append(link(owner + "/datasets"));
        out.append("\nSpaces ").append(link(owner + "/spaces"));

        HttpUrl orgCard = HUB.newBuilder().addPathSegment("spaces").addPathSegment(owner)
                .addPathSegments("README/raw/main/README.md").build();
        appendCard(out, "组织卡片", orgCard, client);

        HttpUrl collections = HUB.newBuilder().addPathSegments("api/collections").addQueryParameter("owner", owner)
                .addQueryParameter("limit", Integer.toString(LIST_LIMIT)).addQueryParameter("sort", "lastModified").build();
        appendListing(out, "合集", link(owner + "/collections"), client, collections, "slug", "title", "collections/");

        HttpUrl models = HUB.newBuilder().addPathSegments("api/models").addQueryParameter("author", owner)
                .addQueryParameter("limit", Integer.toString(LIST_LIMIT)).addQueryParameter("sort", "lastModified")
                .addQueryParameter("direction", "-1").build();
        appendListing(out, "模型", link(owner + "/models"), client, models, "id", "pipeline_tag", "");
        return out.toString();
    }

    private static String model(OkHttpClient client, String owner, String repo) throws IOException {
        HttpUrl metadata = HUB.newBuilder().addPathSegments("api/models").addPathSegment(owner).addPathSegment(repo).build();
        JsonObject info = parse(fetch(client, metadata, JSON, API_LIMIT, false)).getAsJsonObject();
        String modelId = field(info, "id");
        if (!isRepoId(modelId)) modelId = owner + "/" + repo;
        String sha = field(info, "sha");
        String revision = SHA.matcher(sha).matches() ? sha : "main";

        StringBuilder out = new StringBuilder("# ").append(modelId).append("\n模型主页 ").append(link(modelId));
        appendField(out, "任务", field(info, "pipeline_tag"));
        appendField(out, "框架", field(info, "library_name"));
        appendField(out, "更新日期", field(info, "lastModified"));
        appendField(out, "访问限制", field(info, "gated"));
        JsonObject cardData = info.get("cardData") instanceof JsonObject data ? data : new JsonObject();
        String licenseName = field(cardData, "license");
        if (licenseName.isBlank() && info.get("tags") instanceof com.google.gson.JsonArray tags) {
            for (JsonElement tag : tags) {
                if (!tag.isJsonPrimitive() || !tag.getAsString().startsWith("license:")) continue;
                licenseName = tag.getAsString().substring("license:".length());
                break;
            }
        }
        appendField(out, "许可证", licenseName);
        String licenseLink = field(cardData, "license_link");
        if (!licenseLink.isBlank()) {
            HttpUrl license = link(modelId + "/blob/" + revision + "/README.md").resolve(licenseLink);
            if (license != null) appendField(out, "许可证链接", license.toString());
        }
        appendField(out, "提交记录", link(modelId + "/commits/main").toString());
        appendField(out, "文件版本", link(modelId + "/tree/" + revision).toString());
        appendCard(out, "模型卡", link(modelId + "/raw/" + revision + "/README.md"), client);
        return out.toString();
    }

    private static void appendCard(StringBuilder out, String title, HttpUrl url, OkHttpClient client) {
        out.append("\n\n## ").append(title);
        try {
            String card = fetch(client, url, MARKDOWN, CARD_LIMIT, true);
            if (card == null) {
                out.append("\n公开仓库中未找到 README.md。");
            } else if (card.isBlank() || HTML_PAGE.matcher(card).find()) {
                out.append("\nREADME.md 为空或返回了网页，未取得卡片正文。");
            } else {
                out.append("\n来源 ").append(url).append("\n\n").append(card);
            }
        } catch (IOException error) {
            out.append("\n读取失败（").append(reason(error)).append("）。来源 ").append(url);
        }
    }

    private static void appendListing(StringBuilder out, String title, HttpUrl fullPage, OkHttpClient client,
                                      HttpUrl endpoint, String idKey, String nameKey, String prefix) {
        out.append("\n\n## ").append(title).append("（首批最多 ").append(LIST_LIMIT).append(" 项）")
                .append("\n完整列表 ").append(fullPage);
        try {
            var entries = parse(fetch(client, endpoint, JSON, API_LIMIT, false)).getAsJsonArray();
            if (entries.isEmpty()) out.append("\n暂无公开条目。");
            for (int i = 0; i < Math.min(entries.size(), LIST_LIMIT); i++) {
                JsonObject entry = entries.get(i).getAsJsonObject();
                String id = field(entry, idKey);
                if (!isRepoId(id)) continue;
                String label = field(entry, nameKey);
                out.append("\n- ").append(id);
                if (!label.isBlank()) out.append(" · ").append(label);
                out.append(" · ").append(link(prefix + id));
            }
        } catch (IOException | IllegalStateException error) {
            out.append("\n读取失败（").append(reason(error)).append("）。");
        }
    }

    private static String fetch(OkHttpClient client, HttpUrl url, String accept, int limit, boolean allowMissing)
            throws IOException {
        for (int redirects = 0; redirects <= 4; redirects++) {
            Request request = new Request.Builder().url(url).header("Accept", accept).build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    HttpUrl next = location == null ? null : url.resolve(location);
                    if (next == null || !next.isHttps() || next.port() != 443 || !next.host().equals(HUB.host())
                            || !next.username().isEmpty() || !next.password().isEmpty() || next.equals(url)) {
                        throw new FetchError("重定向目标无效");
                    }
                    url = next;
                    continue;
                }
                if (allowMissing && response.code() == 404) return null;
                if (!response.isSuccessful()) throw new FetchError("HTTP " + response.code());
                var body = response.body();
                if (body == null) throw new FetchError("响应为空");
                var type = body.contentType();
                if (type != null && type.subtype().equals("html")) throw new FetchError("接口返回了 HTML 页面");
                if (body.source().request(limit + 1L)) throw new FetchError("超过 " + (limit / 1024) + " KiB 单次读取上限");
                return body.string();
            }
        }
        throw new FetchError("重定向次数过多");
    }

    private static JsonElement parse(String body) throws FetchError {
        try {
            return JsonParser.parseString(body);
        } catch (JsonParseException error) {
            throw new FetchError("返回的 JSON 无效");
        }
    }

    private static HttpUrl link(String path) {
        return HUB.newBuilder().addPathSegments(path).build();
    }

    private static boolean isRepoId(String id) {
        int slash = id.indexOf('/');
        return slash > 0 && NAME.matcher(id.substring(0, slash)).matches() && NAME.matcher(id.substring(slash + 1)).matches();
    }

    private static String field(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static void appendField(StringBuilder out, String title, String value) {
        if (!value.isBlank()) out.append('\n').append(title).append(' ').append(value);
    }

    private static String reason(Exception error) {
        if (error instanceof FetchError) return error.getMessage();
        if (error instanceof IllegalStateException) return "接口返回的数据格式不正确";
        return error.getClass().getSimpleName();
    }

    private static final class FetchError extends IOException {
        private FetchError(String message) { super(message); }
    }
}
