package com.nekoyu.Universe.AIChat.Web.Amap;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import okhttp3.*;

import java.io.IOException;
import java.net.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Fixed Amap endpoints, typed parsing, bounded shared cache and request coalescing. */
public final class Client {
    private static final Set<String> ENDPOINTS = Set.of(
            "config/district", "weather/weatherInfo", "geocode/geo", "geocode/regeo",
            "place/text", "place/around", "direction/driving", "direction/walking");
    private static final int CACHE_SIZE = 256;
    private final String apiKey;
    private final Transport transport;
    private final LongSupplier clock;
    private final int requestsPerMinute;
    private final Gson gson = new GsonBuilder().registerTypeAdapter(String.class, new OptionalStringAdapter()).create();
    private final Map<String, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, CompletableFuture<Responses.Base>> inFlight = new HashMap<>();
    private final Deque<Long> requests = new ArrayDeque<>();
    private record Entry(Responses.Base value, long expires) {}

    @FunctionalInterface
    public interface Transport {
        String get(HttpUrl url) throws IOException;
    }

    public static class QueryException extends IOException {
        public QueryException(String message) { super(message); }
    }

    public Client(String apiKey, Proxy proxy, int requestsPerMinute) {
        this(apiKey, network(proxy), requestsPerMinute, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    /** Injectable transport/time for offline checks, with the same URL and parsing path. */
    public Client(String apiKey, Transport transport, int requestsPerMinute, LongSupplier clock) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("未配置高德 API Key");
        if (requestsPerMinute <= 0) throw new IllegalArgumentException("高德请求限流必须为正数");
        this.apiKey = apiKey.trim();
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
        this.requestsPerMinute = requestsPerMinute;
    }

    private static Transport network(Proxy proxy) {
        OkHttpClient http = new OkHttpClient.Builder().proxy(proxy)
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).build();
        return url -> {
            try (Response response = http.newCall(new Request.Builder().url(url)
                    .header("Accept", "application/json").build()).execute()) {
                if (!response.isSuccessful()) throw new QueryException("HTTP " + response.code());
                if (response.body() == null) throw new QueryException("接口响应为空");
                return response.body().string();
            }
        };
    }

    public <T extends Responses.Base> T get(String endpoint, Map<String, String> params,
                                           Class<T> type, long ttlMillis) throws IOException {
        if (!ENDPOINTS.contains(endpoint)) throw new IllegalArgumentException("不支持的地图接口");
        Map<String, String> sorted = new TreeMap<>(params);
        if (sorted.containsKey("key") || sorted.containsKey("output"))
            throw new IllegalArgumentException("认证和输出格式由客户端管理");
        String cacheKey = endpoint + gson.toJson(sorted) + type.getName();
        CompletableFuture<Responses.Base> pending;
        boolean owner;
        synchronized (cache) {
            long now = clock.getAsLong();
            Entry entry = cache.get(cacheKey);
            if (entry != null && now < entry.expires()) return type.cast(entry.value());
            cache.remove(cacheKey);
            pending = inFlight.get(cacheKey);
            owner = pending == null;
            if (owner) {
                while (!requests.isEmpty() && now - requests.peekFirst() >= 60_000) requests.removeFirst();
                if (requests.size() >= requestsPerMinute) throw new QueryException("本地每分钟请求限额已达到，请稍后再试");
                requests.addLast(now);
                pending = new CompletableFuture<>();
                inFlight.put(cacheKey, pending);
            }
        }
        if (!owner) {
            try { return type.cast(pending.get()); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new QueryException("请求等待被中断");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException error) throw error;
                throw new QueryException("接口响应异常");
            }
        }
        try {
            HttpUrl.Builder url = Objects.requireNonNull(HttpUrl.parse("https://restapi.amap.com/v3/" + endpoint))
                    .newBuilder().addQueryParameter("key", apiKey).addQueryParameter("output", "JSON");
            sorted.forEach(url::addQueryParameter);
            T result = parse(transport.get(url.build()), type);
            synchronized (cache) {
                cache.put(cacheKey, new Entry(result, clock.getAsLong() + ttlMillis));
                while (cache.size() > CACHE_SIZE) cache.remove(cache.keySet().iterator().next());
            }
            pending.complete(result);
            return result;
        } catch (IOException | RuntimeException error) {
            IOException safe = error instanceof QueryException query ? query
                    : new QueryException("网络连接或接口响应异常，请稍后再试");
            pending.completeExceptionally(safe);
            throw safe;
        } finally {
            synchronized (cache) { inFlight.remove(cacheKey); }
        }
    }

    private <T extends Responses.Base> T parse(String raw, Class<T> type) throws IOException {
        try {
            JsonObject object = JsonParser.parseString(raw).getAsJsonObject();
            // Only optional objects are normalized; real list fields remain arrays.
            normalizeObject(object, "route");
            normalizeObject(object, "regeocode");
            if (object.get("regeocode") instanceof JsonObject regeo) normalizeObject(regeo, "addressComponent");
            T result = gson.fromJson(object, type);
            if (result == null || result.status == null) throw new JsonParseException("Missing status");
            if (!"1".equals(result.status) || !"10000".equals(result.infocode)) {
                String code = result.infocode != null && result.infocode.matches("\\d{5}") ? result.infocode : "未知";
                String hint = switch (code) {
                    case "10001", "10002", "10005", "10006", "10007", "10008", "10009", "10012", "10013" -> "密钥、权限或访问限制错误，请检查服务端配置";
                    case "10003", "10004", "10010", "10014", "10019", "10020", "10021" -> "配额不足或请求频率受限";
                    case "20000", "20001", "20002", "20003" -> "请求参数不符合接口要求";
                    default -> "接口拒绝请求或服务暂不可用";
                };
                throw new QueryException(hint + "（infocode=" + code + "）");
            }
            return result;
        } catch (RuntimeException e) {
            throw new QueryException("接口返回无效的 JSON 或字段类型");
        }
    }

    private static void normalizeObject(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value != null && value.isJsonArray() && value.getAsJsonArray().isEmpty()) object.add(field, JsonNull.INSTANCE);
    }

    /** Amap uses [] for absent strings; nonempty arrays are not scalar values. */
    private static class OptionalStringAdapter extends TypeAdapter<String> {
        public String read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
            if (in.peek() == JsonToken.BEGIN_ARRAY) {
                in.beginArray();
                if (in.hasNext()) throw new JsonParseException("Nonempty array in scalar field");
                in.endArray(); return null;
            }
            return in.nextString();
        }
        public void write(JsonWriter out, String value) throws IOException { out.value(value); }
    }
}
