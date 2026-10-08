import com.google.gson.JsonParser;
import com.nekoyu.Universe.AIChat.Web.Amap.*;
import com.nekoyu.Universe.AIChat.Web.Amap.Responses.*;
import okhttp3.HttpUrl;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Offline URL, parsing, ambiguity, cache, limit and concurrency acceptance checks. */
public class MapServiceTest {
    static final String KEY = "secret-test-key";
    static final String HANGZHOU = "{\"status\":\"1\",\"infocode\":\"10000\",\"count\":\"1\",\"districts\":[{\"name\":\"杭州市\",\"adcode\":\"330100\",\"level\":\"city\",\"center\":\"120.15507,30.274084\",\"districts\":[]}]}";
    static final String LIVE = "{\"status\":\"1\",\"infocode\":\"10000\",\"lives\":[{\"province\":\"浙江省\",\"city\":\"杭州市\",\"adcode\":\"330100\",\"weather\":\"晴\",\"temperature\":\"25\",\"humidity\":\"60\",\"winddirection\":\"东\",\"windpower\":\"≤3\",\"reporttime\":\"2026-10-02 16:00:00\"}]}";
    static final String FORECAST = """
            {"status":"1","infocode":"10000","forecasts":[{"city":"杭州市","reporttime":"2026-10-02 11:00:00","casts":[
            {"date":"2026-10-02","dayweather":"晴","nightweather":"多云","daytemp":"25","nighttemp":"18"},
            {"date":"2026-10-03","dayweather":"小雨","nightweather":"阴","daytemp":"24","nighttemp":"17"},
            {"date":"2026-10-04","dayweather":"阴"},{"date":"2026-10-05","dayweather":"多云"}]}]}
            """;
    static final String GEO = "{\"status\":\"1\",\"infocode\":\"10000\",\"geocodes\":[{\"formatted_address\":\"浙江省杭州市测试地址\",\"province\":\"浙江省\",\"city\":[],\"district\":[],\"adcode\":\"330100\",\"location\":\"120.15507,30.274084\",\"level\":\"兴趣点\"}]}";
    static final String ROUTE = "{\"status\":\"1\",\"infocode\":\"10000\",\"route\":{\"paths\":[{\"distance\":\"1400\",\"duration\":\"1800\",\"tolls\":[],\"steps\":[{\"instruction\":\"沿测试道路向南步行\",\"road\":[]}]}]}}";

    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static String query(MapService service, String json) { return service.query(JsonParser.parseString(json)); }
    static String fixture(HttpUrl url) throws IOException {
        check(url.host().equals("restapi.amap.com") && url.isHttps(), "fixed HTTPS host");
        check(KEY.equals(url.queryParameter("key")) && "JSON".equals(url.queryParameter("output")), "managed authentication/format");
        return switch (url.encodedPath()) {
            case "/v3/config/district" -> {
                check("0".equals(url.queryParameter("subdistrict")), "only matching districts");
                String keywords = url.queryParameter("keywords");
                if ("北京市".equals(keywords)) yield "{\"status\":\"1\",\"infocode\":\"10000\",\"districts\":[{\"name\":\"北京市\",\"adcode\":\"110000\",\"level\":\"province\"}]}";
                if ("朝阳区".equals(keywords)) {
                    if ("110000".equals(url.queryParameter("filter"))) yield "{\"status\":\"1\",\"infocode\":\"10000\",\"districts\":[{\"name\":\"朝阳区\",\"adcode\":\"110105\",\"level\":\"district\"}]}";
                    yield "{\"status\":\"1\",\"infocode\":\"10000\",\"districts\":[{\"name\":\"朝阳区\",\"adcode\":\"110105\",\"level\":\"district\"},{\"name\":\"朝阳区\",\"adcode\":\"220104\",\"level\":\"district\"}]}";
                }
                if ("浙江省".equals(keywords)) yield "{\"status\":\"1\",\"infocode\":\"10000\",\"districts\":[{\"name\":\"浙江省\",\"adcode\":\"330000\",\"level\":\"province\"}]}";
                if ("不存在".equals(keywords)) yield "{\"status\":\"1\",\"infocode\":\"10000\",\"districts\":[]}";
                yield HANGZHOU;
            }
            case "/v3/weather/weatherInfo" -> "all".equals(url.queryParameter("extensions")) ? FORECAST : LIVE;
            case "/v3/geocode/geo" -> GEO;
            case "/v3/geocode/regeo" -> "{\"status\":\"1\",\"infocode\":\"10000\",\"regeocode\":{\"formatted_address\":\"北京市海淀区测试地址\",\"addressComponent\":{\"province\":\"北京市\",\"city\":[],\"district\":\"海淀区\",\"adcode\":\"110108\"}}}";
            case "/v3/place/text", "/v3/place/around" -> {
                check("杭州 & coffee".equals(url.queryParameter("keywords")), "keywords encoded without injecting query params");
                if (url.encodedPath().endsWith("/text")) check("true".equals(url.queryParameter("citylimit")), "city boundary");
                else check("3000".equals(url.queryParameter("radius")) && "distance".equals(url.queryParameter("sortrule")), "around scope");
                yield "{\"status\":\"1\",\"infocode\":\"10000\",\"pois\":[{\"id\":\"BTEST\",\"name\":\"测试咖啡店\",\"address\":\"测试街1号\",\"location\":\"120.15507,30.274084\",\"tel\":[],\"distance\":\"100\"}]}";
            }
            case "/v3/direction/driving", "/v3/direction/walking" -> ROUTE;
            default -> throw new AssertionError("unexpected endpoint");
        };
    }

    public static void main(String[] args) throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicInteger calls = new AtomicInteger(), weatherCalls = new AtomicInteger();
        Client client = new Client(KEY, url -> { calls.incrementAndGet(); if (url.encodedPath().contains("weather")) weatherCalls.incrementAndGet(); return fixture(url); }, 1000, clock::get);
        MapService service = new MapService(client);
        for (String invalid : new String[]{"null", "[]", "{}", "{\"action\":\"weather\"}",
                "{\"action\":\"weather\",\"location\":12}", "{\"action\":\"weather\",\"location\":\"杭州\",\"type\":\"hourly\"}",
                "{\"action\":\"search\",\"keywords\":\"咖啡\"}", "{\"action\":\"regeocode\",\"location\":\"181,30\"}",
                "{\"action\":\"regeocode\",\"location\":\"NaN,30\"}", "{\"action\":\"regeocode\",\"location\":\"120.1234567,30\"}",
                "{\"action\":\"district\",\"location\":\"杭州\",\"limit\":1.5}", "{\"action\":\"geocode\",\"location\":\"杭州\",\"limit\":\"5\"}",
                "{\"action\":\"search\",\"keywords\":\"咖啡\",\"location\":\"120,30\",\"radius\":50001}",
                "{\"action\":\"route\",\"origin\":\"杭州\",\"destination\":\"120,91\"}",
                "{\"action\":\"route\",\"origin\":\"120,30\",\"destination\":\"121,30\",\"travelMode\":\"transit\"}",
                "{\"action\":\"weather\",\"location\":\"杭州\",\"URL\":\"https://example.com\"}"})
            check(query(service, invalid).contains("参数错误"), "invalid input rejected: " + invalid);
        check(calls.get() == 0, "invalid input never spends quota");

        String weather = query(service, "{\"action\":\"weather\",\"location\":\"杭州市\",\"type\":\"both\"}");
        check(weather.contains("25℃") && weather.contains("60%") && weather.contains("reporttime=2026-10-02 16:00:00"), "live units and publication time");
        check(weather.contains("2026-10-05") && weather.contains("小雨") && !weather.contains("secret-test-key"), "all returned forecast days and no credentials");
        check(calls.get() == 3, "district then two weather modes");
        query(service, "{\"action\":\"weather\",\"location\":\"杭州市\",\"type\":\"both\"}");
        check(calls.get() == 3, "repeat cached");
        clock.set(5 * 60_000);
        query(service, "{\"action\":\"weather\",\"location\":\"杭州市\",\"type\":\"both\"}");
        check(calls.get() == 4, "live expires before forecast and district");
        clock.set(30 * 60_000);
        query(service, "{\"action\":\"weather\",\"location\":\"杭州市\",\"type\":\"both\"}");
        check(calls.get() == 6, "forecast expiry boundary");

        int beforeAmbiguity = weatherCalls.get();
        String ambiguous = query(service, "{\"action\":\"weather\",\"location\":\"朝阳区\"}");
        check(ambiguous.contains("需要确认") && ambiguous.contains("110105") && ambiguous.contains("220104"), "ambiguous district candidates");
        query(service, "{\"action\":\"weather\",\"location\":\"浙江省\"}");
        query(service, "{\"action\":\"weather\",\"location\":\"不存在\"}");
        check(weatherCalls.get() == beforeAmbiguity, "ambiguity, province and empty district cannot query weather");
        check(!query(service, "{\"action\":\"weather\",\"location\":\"朝阳区\",\"province\":\"北京市\"}").contains("需要确认"), "province filters district");
        check(query(service, "{\"action\":\"weather\",\"location\":\"330100\"}").contains("杭州市"), "explicit adcode");

        check(query(service, "{\"action\":\"district\",\"location\":\"杭州市\"}").contains("330100"), "district operation");
        check(query(service, "{\"action\":\"geocode\",\"location\":\"测试地址\"}").contains("120.15507,30.274084"), "geocode empty scalar arrays accepted");
        check(query(service, "{\"action\":\"regeocode\",\"location\":\"116.310003,39.991957\"}").contains("海淀区"), "municipality empty city accepted");
        check(query(service, "{\"action\":\"search\",\"city\":\"杭州市\",\"keywords\":\"杭州 & coffee\"}").contains("测试咖啡店"), "POI text operation");
        check(query(service, "{\"action\":\"search\",\"location\":\"120.15507,30.274084\",\"keywords\":\"杭州 & coffee\"}").contains("距中心 100米"), "around operation");
        check(query(service, "{\"action\":\"route\",\"origin\":\"120.15507,30.274084\",\"destination\":\"120.16007,30.270084\",\"travelMode\":\"walking\"}").contains("1800秒"), "walking distance/time");
        check(query(service, "{\"action\":\"route\",\"origin\":\"测试地址\",\"destination\":\"120.16007,30.270084\"}").contains("驾车"), "address to driving route");

        Client empty = new Client(KEY, url -> "{\"status\":\"1\",\"infocode\":\"10000\",\"geocodes\":[],\"route\":[],\"regeocode\":[]}", 100, clock::get);
        check(empty.get("geocode/geo", Map.of(), GeocodeResponse.class, 1).geocodes.isEmpty(), "actual list stays empty array");
        check(empty.get("direction/driving", Map.of(), RouteResponse.class, 1).route == null, "optional empty object normalized");
        AtomicInteger failedCalls = new AtomicInteger();
        MapService failing = new MapService(new Client(KEY, url -> { failedCalls.incrementAndGet(); return "{\"status\":\"0\",\"infocode\":\"10001\",\"info\":\"" + KEY + "\"}"; }, 100, clock::get));
        for (int i = 0; i < 2; i++) {
            String error = query(failing, "{\"action\":\"district\",\"location\":\"杭州\"}");
            check(error.contains("10001") && !error.contains(KEY), "business error sanitized");
        }
        check(failedCalls.get() == 2, "failure never cached");
        for (String bad : new String[]{"<html>error</html>", "{}", "{\"status\":\"1\",\"infocode\":\"10000\",\"geocodes\":[{\"city\":[\"unexpected\"]}]}"}) {
            MapService malformed = new MapService(new Client(KEY, url -> bad, 100, clock::get));
            check(query(malformed, "{\"action\":\"geocode\",\"location\":\"测试地址\"}").contains("查询失败"), "malformed response rejected");
        }
        MapService partial = new MapService(new Client(KEY, url -> {
            if (url.encodedPath().contains("weather") && "base".equals(url.queryParameter("extensions"))) throw new IOException(KEY);
            return fixture(url);
        }, 100, clock::get));
        String partly = query(partial, "{\"action\":\"weather\",\"location\":\"杭州\",\"type\":\"both\"}");
        check(partly.contains("实况查询失败") && partly.contains("2026-10-03") && !partly.contains(KEY), "partial weather survives and exception redacted");

        AtomicLong rateClock = new AtomicLong();
        Client limited = new Client(KEY, MapServiceTest::fixture, 1, rateClock::get);
        limited.get("config/district", Map.of("keywords", "杭州", "subdistrict", "0"), DistrictResponse.class, 100_000);
        limited.get("config/district", Map.of("keywords", "杭州", "subdistrict", "0"), DistrictResponse.class, 100_000);
        try { limited.get("geocode/geo", Map.of(), GeocodeResponse.class, 1); throw new AssertionError("expected rate limit"); }
        catch (Client.QueryException e) { check(e.getMessage().contains("每分钟"), "local limit"); }
        rateClock.set(60_000);
        limited.get("geocode/geo", Map.of(), GeocodeResponse.class, 1);
        concurrentCoalescing();
        System.out.println("MapServiceTest passed");
    }

    private static void concurrentCoalescing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Client client = new Client(KEY, url -> {
            calls.incrementAndGet(); entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            return LIVE;
        }, 1, () -> 0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> follower = new AtomicReference<>();
        try {
            Future<WeatherResponse> first = pool.submit(() -> client.get("weather/weatherInfo", Map.of("city", "330100"), WeatherResponse.class, 0));
            check(entered.await(5, TimeUnit.SECONDS), "first query entered");
            Future<WeatherResponse> second = pool.submit(() -> {
                follower.set(Thread.currentThread());
                return client.get("weather/weatherInfo", Map.of("city", "330100"), WeatherResponse.class, 0);
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((follower.get() == null || follower.get().getState() != Thread.State.WAITING) && System.nanoTime() < deadline) Thread.sleep(1);
            check(follower.get() != null && follower.get().getState() == Thread.State.WAITING, "follower awaiting same in-flight query");
            release.countDown();
            check(first.get(5, TimeUnit.SECONDS).lives.size() == 1 && second.get(5, TimeUnit.SECONDS).lives.size() == 1, "both receive result");
            check(calls.get() == 1, "coalesced request consumes one quota slot even without cache TTL");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
