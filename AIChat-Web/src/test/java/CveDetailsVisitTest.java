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

public class CveDetailsVisitTest {
    private static final String ID = "CVE-2024-38063";
    private static final String NVD = "https://nvd.nist.gov/vuln/detail/";
    private static final String CIRCL = "https://cve.circl.lu/cve/";
    private static final String REFERENCE = "https://example.org/advisory?id=42&language=en";
    private static final String NVD_RECORD = """
            {"id":"CVE-2024-38063","vulnStatus":"Analyzed","descriptions":[{"lang":"en","value":"NVD description"}],
            "metrics":{"cvssMetricV31":[{"cvssData":{"baseScore":9.8}}]},
            "configurations":[{"nodes":[{"operator":"AND","cpeMatch":[{"vulnerable":true,"versionEndExcluding":"3.4"}]}]}],
            "padding":"%s","references":[{"url":"%s"}]}
            """.formatted("x".repeat(6000), REFERENCE);
    private static final String CIRCL_RECORD = """
            {"dataType":"CVE_RECORD","cveMetadata":{"cveId":"CVE-2024-38063","state":"PUBLISHED"},
            "containers":{"cna":{"descriptions":[{"lang":"en","value":"CNA description"}],
            "affected":[{"product":"Sample","versions":[{"version":"3.0","lessThan":"3.4","status":"affected"}]}],
            "references":[{"url":"%s"}]},"adp":[{"providerMetadata":{"shortName":"ADP"}}]}}
            """.formatted(REFERENCE);

    public static void main(String[] args) throws Exception {
        var web = new Web(null);
        setField(web, "config", new Config());
        List<String> requested = new ArrayList<>();
        var proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080));
        setField(web, "client", new OkHttpClient.Builder().proxy(proxy).addInterceptor(chain -> {
            Request request = chain.request();
            requested.add(request.url().toString());
            if (request.url().host().equals("services.nvd.nist.gov")) {
                return switch (request.url().queryParameter("cveId")) {
                    case ID -> reply(request, 200, "{\"vulnerabilities\":[{\"cve\":" + NVD_RECORD + "}]}");
                    case "CVE-2024-0001" -> reply(request, 200, "{\"vulnerabilities\":[]}");
                    default -> throw new AssertionError("Unexpected NVD request");
                };
            }
            check(request.url().host().equals("vulnerability.circl.lu"), "fixed CIRCL API host");
            return switch (request.url().encodedPath()) {
                case "/api/vulnerability/" + ID -> reply(request, 200, CIRCL_RECORD);
                case "/api/vulnerability/CVE-2024-0001" -> reply(request, 404, "{}");
                default -> throw new AssertionError("Unexpected CIRCL request");
            };
        }).build());
        Method visit = Web.class.getDeclaredMethod("visitUrl", String.class);
        visit.setAccessible(true);

        String nvd = visit.invoke(web, NVD + ID.toLowerCase(java.util.Locale.ROOT) + "?example=true#metrics").toString();
        check(nvd.contains("NVD description") && nvd.contains("cvssMetricV31") && nvd.contains("\"operator\":\"AND\"")
                && nvd.contains("versionEndExcluding") && nvd.contains(REFERENCE), "NVD keeps applicability and late references");
        for (String page : List.of(CIRCL + ID, "https://vulnerability.circl.lu/vuln/" + ID)) {
            String circl = visit.invoke(web, page).toString();
            check(circl.contains("CNA description") && circl.contains("\"lessThan\":\"3.4\"")
                    && circl.contains("\"adp\"") && circl.contains(REFERENCE), "CIRCL preserves CNA and ADP data");
        }
        check(visit.invoke(web, NVD + "CVE-2024-0001").toString().contains("未取得 NVD"), "empty result reported");
        check(visit.invoke(web, CIRCL + "CVE-2024-0001").toString().contains("HTTP 404"), "CIRCL status reported");
        check(visit.invoke(web, NVD + "not-a-cve").toString().contains("不支持的链接类型"), "unmatched page uses generic route");
        check(requested.equals(List.of("https://services.nvd.nist.gov/rest/json/cves/2.0?cveId=" + ID,
                "https://vulnerability.circl.lu/api/vulnerability/" + ID,
                "https://vulnerability.circl.lu/api/vulnerability/" + ID,
                "https://services.nvd.nist.gov/rest/json/cves/2.0?cveId=CVE-2024-0001",
                "https://vulnerability.circl.lu/api/vulnerability/CVE-2024-0001")), "exact source routes");
        System.out.println("CveDetailsVisitTest passed");
    }

    private static Response reply(Request request, int status, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(ResponseBody.create(body, MediaType.get("application/json; charset=utf-8"))).build();
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
