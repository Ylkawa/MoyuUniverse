package com.nekoyu.Universe.AIChat.Web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

final class CveDetails {
    private static final Pattern NVD_PAGE = Pattern.compile("/vuln/detail/(CVE-[0-9]{4}-[0-9]{4,})/?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CIRCL_PAGE = Pattern.compile("/(?:cve|vuln)/(CVE-[0-9]{4}-[0-9]{4,})/?", Pattern.CASE_INSENSITIVE);
    private static final HttpUrl NVD_API = HttpUrl.get("https://services.nvd.nist.gov/rest/json/cves/2.0");
    private static final HttpUrl CIRCL_API = HttpUrl.get("https://vulnerability.circl.lu/api/vulnerability/");
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_BYTES = 256 * 1024;

    private CveDetails() {}

    /** Routes individual CVE pages to the respective database's public record endpoint. */
    static String visit(String url, OkHttpClient webClient) {
        HttpUrl page = HttpUrl.parse(url);
        if (page == null) return null;
        boolean nvd = page.host().equals("nvd.nist.gov") || page.host().equals("web.nvd.nist.gov");
        if (!nvd && !page.host().equals("cve.circl.lu") && !page.host().equals("vulnerability.circl.lu")) return null;
        var match = (nvd ? NVD_PAGE : CIRCL_PAGE).matcher(page.encodedPath());
        if (!match.matches()) return null;

        String id = match.group(1).toUpperCase(Locale.ROOT);
        String source = nvd ? "NVD" : "CIRCL";
        HttpUrl endpoint = nvd ? NVD_API.newBuilder().addQueryParameter("cveId", id).build()
                : CIRCL_API.newBuilder().addPathSegment(id).build();
        Request request = new Request.Builder().url(endpoint).header("Accept", "application/json").build();
        OkHttpClient client = webClient.newBuilder().followRedirects(false).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return failure(source, id, "HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) return failure(source, id, "API 返回为空");
            if (body.source().request(MAX_BYTES + 1L)) return failure(source, id, "记录超过 256 KiB 的单次读取上限");
            JsonObject payload = JsonParser.parseReader(body.charStream()).getAsJsonObject();
            JsonObject record = nvd ? nvdRecord(payload, id) : circlRecord(payload, id);
            if (record == null) return failure(source, id, "API 未返回该编号的记录");

            // 保留受影响范围、版本条件和评分的原始结构，避免展平后改变含义。
            return "# " + id + " · " + source + "\n来源 " + page + "\n\n```json\n" + JSON.toJson(record) + "\n```";
        } catch (IOException | JsonParseException | IllegalStateException error) {
            return failure(source, id, error.getClass().getSimpleName());
        }
    }

    private static JsonObject nvdRecord(JsonObject payload, String id) {
        var entries = payload.get("vulnerabilities");
        if (entries == null || !entries.isJsonArray()) throw new JsonParseException("Expected NVD vulnerabilities");
        for (var entry : entries.getAsJsonArray()) {
            var record = entry.getAsJsonObject().get("cve");
            if (record != null && record.isJsonObject() && hasId(record.getAsJsonObject(), "id", id)) {
                return record.getAsJsonObject();
            }
        }
        return null;
    }

    private static JsonObject circlRecord(JsonObject payload, String id) {
        var metadata = payload.get("cveMetadata");
        return metadata != null && metadata.isJsonObject() && hasId(metadata.getAsJsonObject(), "cveId", id) ? payload : null;
    }

    private static boolean hasId(JsonObject object, String field, String id) {
        var value = object.get(field);
        return value != null && value.isJsonPrimitive() && id.equalsIgnoreCase(value.getAsString());
    }

    private static String failure(String source, String id, String reason) {
        return "未取得 " + source + " 的 " + id + " 记录（" + reason + "）。";
    }
}
