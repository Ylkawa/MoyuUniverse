package com.nekoyu.Universe.AIChat.Web.Amap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nekoyu.Universe.AIChat.Web.Amap.Responses.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** One map query dispatcher shared by the map skill; returns compact, sourced text. */
public final class MapService {
    private static final long MINUTE = 60_000;
    private final Client client;

    public MapService(Client client) { this.client = Objects.requireNonNull(client); }

    public String query(JsonElement input) {
        try {
            if (input == null || !input.isJsonObject()) throw new IllegalArgumentException("需要 JSON 对象参数");
            JsonObject args = input.getAsJsonObject();
            String action = required(args, "action");
            validateFields(args, action);
            String result = switch (action) {
                case "weather" -> weather(args);
                case "district" -> district(args);
                case "geocode" -> geocode(args);
                case "regeocode" -> regeocode(args);
                case "search" -> search(args);
                case "route" -> route(args);
                default -> throw new IllegalArgumentException("不支持的 action");
            };
            return "[高德地图 / " + action + "]\n" + result
                    + "\n来源：高德 Web 服务 API；查询时间："
                    + LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    + "（可能使用缓存；天气发布时间见 reporttime）。";
        } catch (IllegalArgumentException error) {
            return "地图查询参数错误：" + error.getMessage();
        } catch (Clarification error) {
            return "地图查询需要确认：" + error.getMessage();
        } catch (Client.QueryException error) {
            return "高德地图查询失败：" + error.getMessage();
        } catch (IOException | RuntimeException error) {
            return "高德地图查询失败：网络或响应异常，请稍后再试。";
        }
    }

    private String weather(JsonObject args) throws IOException, Clarification {
        String location = required(args, "location");
        String province = optional(args, "province");
        String type = choice(args, "type", "live", "live", "forecast", "both");
        District place = resolveDistrict(location, province);
        if (!Set.of("province", "city", "district", "street").contains(text(place.level)))
            throw new Clarification("请提供城市或区县，不能查询全国天气。");
        if ("province".equals(place.level) && !Set.of("110000", "120000", "310000", "500000").contains(place.adcode))
            throw new Clarification("请补充 " + text(place.name) + " 内的城市或区县。");
        StringBuilder result = new StringBuilder("查询地区：").append(text(place.name)).append("（adcode=").append(place.adcode).append("）\n");
        if (!"forecast".equals(type)) {
            try {
                WeatherResponse live = client.get("weather/weatherInfo", Map.of("city", place.adcode, "extensions", "base"), WeatherResponse.class, 5 * MINUTE);
                if (items(live.lives).isEmpty()) result.append("暂无实况天气。\n");
                for (Live item : items(live.lives)) result.append(text(item.province)).append(" ").append(text(item.city))
                        .append(" 实况（reporttime=").append(text(item.reporttime)).append("）：")
                        .append(text(item.weather)).append("；气温 ").append(unit(item.temperature, "℃"))
                        .append("；风向 ").append(text(item.winddirection)).append("，风力 ").append(unit(item.windpower, "级"))
                        .append("；湿度 ").append(unit(item.humidity, "%")).append("。\n");
            } catch (IOException e) {
                if (!"both".equals(type)) throw e;
                result.append("实况查询失败：").append(safeError(e)).append("。\n");
            }
        }
        if (!"live".equals(type)) {
            try {
                WeatherResponse forecast = client.get("weather/weatherInfo", Map.of("city", place.adcode, "extensions", "all"), WeatherResponse.class, 30 * MINUTE);
                if (items(forecast.forecasts).isEmpty()) result.append("暂无天气预报。\n");
                for (Forecast item : items(forecast.forecasts)) {
                    result.append(text(item.province)).append(" ").append(text(item.city)).append(" 预报（reporttime=").append(text(item.reporttime)).append("）：\n");
                    if (items(item.casts).isEmpty()) result.append("暂无逐日预报。\n");
                    for (Cast cast : items(item.casts)) result.append(text(cast.date)).append("：白天 ")
                            .append(text(cast.dayweather)).append(" ").append(unit(cast.daytemp, "℃"))
                            .append("，").append(text(cast.daywind)).append("风 ").append(unit(cast.daypower, "级"))
                            .append("；夜间 ").append(text(cast.nightweather)).append(" ").append(unit(cast.nighttemp, "℃"))
                            .append("，").append(text(cast.nightwind)).append("风 ").append(unit(cast.nightpower, "级")).append("。\n");
                }
                result.append("预报范围仅限以上实际返回的日期。\n");
            } catch (IOException e) {
                if (!"both".equals(type)) throw e;
                result.append("预报查询失败：").append(safeError(e)).append("。\n");
            }
        }
        return result.toString();
    }

    private String district(JsonObject args) throws IOException, Clarification {
        String location = required(args, "location");
        int limit = integer(args, "limit", 5, 1, 10);
        List<District> matches = districts(location, optional(args, "province"));
        if (matches.isEmpty()) return "未找到匹配行政区，请补充所属省市或使用准确的 adcode。";
        StringBuilder result = new StringBuilder();
        for (District item : matches.stream().limit(limit).toList()) result.append(districtLabel(item)).append("\n");
        if (matches.size() > 1) result.append("存在多个候选，请确认所属地区后再查询天气或路线。\n");
        if (matches.size() > limit) result.append("候选仅展示前 ").append(limit).append(" 项。\n");
        return result.toString();
    }

    private List<District> districts(String location, String province) throws IOException, Clarification {
        Map<String, String> params = new HashMap<>();
        params.put("keywords", location); params.put("subdistrict", "0"); params.put("extensions", "base");
        params.put("offset", "20"); params.put("page", "1");
        String filter = null;
        if (province != null) {
            DistrictResponse response = client.get("config/district", Map.of("keywords", province, "subdistrict", "0", "extensions", "base"), DistrictResponse.class, 1440 * MINUTE);
            List<District> provinces = items(response.districts).stream().filter(p -> "province".equals(p.level) && validAdcode(p.adcode)).toList();
            if (provinces.size() != 1) throw new Clarification("province 必须指定唯一的省份或直辖市。");
            filter = provinces.get(0).adcode;
            params.put("filter", filter);
        }
        DistrictResponse response = client.get("config/district", params, DistrictResponse.class, 1440 * MINUTE);
        final String provinceCode = filter;
        List<District> result = items(response.districts).stream().filter(p -> validAdcode(p.adcode))
                .filter(p -> provinceCode == null || p.adcode.startsWith(provinceCode.substring(0, 2)))
                .filter(p -> !location.matches("\\d{6}") || location.equals(p.adcode)).toList();
        // Refuse truncated candidate lists rather than treating the first page as a unique match.
        if (number(response.count) > 20) throw new Clarification("匹配地区过多，请提供 province 或更具体的区县名称。");
        return result;
    }

    private District resolveDistrict(String location, String province) throws IOException, Clarification {
        List<District> matches = districts(location, province);
        if (matches.size() != 1) {
            String candidates = matches.stream().limit(10).map(MapService::districtLabel).reduce((a, b) -> a + "\n" + b).orElse("无匹配结果");
            throw new Clarification("地点“" + location + "”无法唯一确定，请补充省市或使用候选 adcode：\n" + candidates);
        }
        return matches.get(0);
    }

    private String geocode(JsonObject args) throws IOException {
        String location = required(args, "location");
        int limit = integer(args, "limit", 5, 1, 10);
        List<Geocode> matches = geocodes(location, optional(args, "city"));
        if (matches.isEmpty()) return "未找到地址，请提供更完整的省、市、区县和地址。";
        StringBuilder result = new StringBuilder("坐标系：高德 GCJ-02，经度在前、纬度在后。\n");
        for (Geocode item : matches.stream().limit(limit).toList()) result.append(geocodeLabel(item)).append("\n");
        if (matches.size() > 1) result.append("存在多个匹配，规划路线前需确认具体地点。\n");
        return result.toString();
    }

    private List<Geocode> geocodes(String location, String city) throws IOException {
        Map<String, String> params = new HashMap<>(); params.put("address", location);
        if (city != null) params.put("city", city);
        return items(client.get("geocode/geo", params, GeocodeResponse.class, 60 * MINUTE).geocodes);
    }

    private String regeocode(JsonObject args) throws IOException {
        String location = coordinate(required(args, "location"));
        Regeocode item = client.get("geocode/regeo", Map.of("location", location, "extensions", "base"), RegeocodeResponse.class, 60 * MINUTE).regeocode;
        if (item == null) return "未找到该坐标的地址。";
        StringBuilder result = new StringBuilder("坐标（GCJ-02）：").append(location)
                .append("\n地址：").append(text(item.formatted_address));
        AddressComponent address = item.addressComponent;
        if (address != null) result.append("\n行政区：").append(text(address.province)).append(" / ")
                .append(text(address.city)).append(" / ").append(text(address.district))
                .append("；adcode=").append(text(address.adcode));
        return result.toString();
    }

    private String search(JsonObject args) throws IOException {
        String keywords = required(args, "keywords");
        String location = optional(args, "location"), city = optional(args, "city");
        int limit = integer(args, "limit", 5, 1, 10);
        int radius = integer(args, "radius", 3000, 1, 50_000);
        if (location == null && city == null) throw new IllegalArgumentException("地点搜索需要 city；周边搜索需要 location 坐标");
        if (location == null && args.has("radius")) throw new IllegalArgumentException("radius 仅适用于提供 location 坐标的周边搜索");
        Map<String, String> params = new HashMap<>(); params.put("keywords", keywords);
        params.put("offset", String.valueOf(limit)); params.put("page", "1"); params.put("extensions", "all");
        if (city != null) { params.put("city", city); params.put("citylimit", "true"); }
        String endpoint = "place/text";
        if (location != null) {
            endpoint = "place/around"; params.put("location", coordinate(location));
            params.put("radius", String.valueOf(radius)); params.put("sortrule", "distance");
        }
        PoiResponse response = client.get(endpoint, params, PoiResponse.class, 5 * MINUTE);
        if (items(response.pois).isEmpty()) return "未找到匹配地点，请调整关键词或查询范围。";
        StringBuilder result = new StringBuilder("地点候选（最多 ").append(limit).append(" 项，坐标系 GCJ-02）：\n");
        for (Poi item : items(response.pois).stream().limit(limit).toList()) result.append(text(item.name))
                .append("（POI ID=").append(text(item.id)).append("）\n  地区：").append(text(item.pname)).append(" ")
                .append(text(item.cityname)).append(" ").append(text(item.adname)).append("；地址：").append(text(item.address))
                .append("\n  坐标：").append(text(item.location)).append("；类型：").append(text(item.type))
                .append("；电话：").append(text(item.tel))
                .append(location != null ? "；距中心 " + unit(item.distance, "米") : "").append("\n");
        result.append("结果是地点候选；周边距离为距查询中心的距离，不是路线距离。未返回营业状态、评分或价格时不能推断这些信息。");
        return result.toString();
    }

    private String route(JsonObject args) throws IOException, Clarification {
        String origin = required(args, "origin"), destination = required(args, "destination");
        String city = optional(args, "city");
        String mode = choice(args, "travelMode", "driving", "driving", "walking");
        // Validate both coordinate-shaped inputs before spending requests on address resolution.
        if (origin.contains(",")) coordinate(origin);
        if (destination.contains(",")) coordinate(destination);
        String from = resolveCoordinate(origin, city), to = resolveCoordinate(destination, city);
        Map<String, String> params = new HashMap<>(Map.of("origin", from, "destination", to));
        if ("driving".equals(mode)) params.put("extensions", "base");
        RouteResponse response = client.get("direction/" + mode, params, RouteResponse.class, MINUTE);
        if (response.route == null || items(response.route.paths).isEmpty()) return "未找到可用路线。";
        StringBuilder result = new StringBuilder("方式：").append(mode.equals("walking") ? "步行" : "驾车")
                .append("；起点：").append(origin).append("（").append(from).append("）；终点：").append(destination).append("（").append(to).append("）\n");
        int index = 0;
        for (Path path : items(response.route.paths).stream().limit(3).toList()) {
            result.append("方案 ").append(++index).append("：距离 ").append(unit(path.distance, "米"))
                    .append("；预计耗时 ").append(unit(path.duration, "秒"));
            if (path.tolls != null && !path.tolls.isBlank()) result.append("；道路收费 ").append(unit(path.tolls, "元"));
            if ("1".equals(path.restriction)) result.append("；包含无法规避的限行路段");
            result.append("\n");
            for (Step step : items(path.steps).stream().limit(12).toList()) result.append("  ").append(text(step.instruction)).append("\n");
            if (items(path.steps).size() > 12) result.append("  其余路段省略，共 ").append(path.steps.size()).append(" 段。\n");
        }
        result.append("以上为路线规划估算；坐标系 GCJ-02，不代表实时导航或准确到达时间。");
        return result.toString();
    }

    private String resolveCoordinate(String location, String city) throws IOException, Clarification {
        if (location.contains(",")) return coordinate(location);
        List<Geocode> matches = geocodes(location, city);
        if (matches.size() != 1) throw new Clarification("路线地点“" + location + "”无法唯一确定，请提供完整地址或先搜索 POI 后使用其坐标：\n"
                + matches.stream().limit(5).map(MapService::geocodeLabel).reduce((a, b) -> a + "\n" + b).orElse("无匹配结果"));
        Geocode match = matches.get(0);
        if (Set.of("国家", "省", "市", "区县").contains(text(match.level)))
            throw new Clarification("路线地点“" + location + "”仅匹配到 " + text(match.level) + "，请提供具体地址或 POI 坐标。");
        return coordinate(match.location);
    }

    private static void validateFields(JsonObject args, String action) {
        Set<String> allowed = switch (action) {
            case "weather" -> Set.of("action", "location", "province", "type");
            case "district" -> Set.of("action", "location", "province", "limit");
            case "geocode" -> Set.of("action", "location", "city", "limit");
            case "regeocode" -> Set.of("action", "location");
            case "search" -> Set.of("action", "keywords", "city", "location", "radius", "limit");
            case "route" -> Set.of("action", "origin", "destination", "city", "travelMode");
            default -> throw new IllegalArgumentException("action 必须为 weather/district/geocode/regeocode/search/route");
        };
        for (String field : args.keySet()) if (!allowed.contains(field)) throw new IllegalArgumentException("当前 action 不支持参数 " + field);
    }

    private static String required(JsonObject args, String name) {
        String value = optional(args, name);
        if (value == null) throw new IllegalArgumentException("缺少 " + name + "，请先向用户确认");
        return value;
    }

    private static String optional(JsonObject args, String name) {
        JsonElement value = args.get(name);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(name + " 必须为字符串");
        String result = value.getAsString().trim();
        if (result.isEmpty() || result.length() > 300) throw new IllegalArgumentException(name + " 长度必须为 1～300 字符");
        return result;
    }

    private static String choice(JsonObject args, String name, String fallback, String... choices) {
        String value = optional(args, name);
        if (value == null) return fallback;
        if (!Arrays.asList(choices).contains(value)) throw new IllegalArgumentException(name + " 必须为 " + String.join("/", choices));
        return value;
    }

    private static int integer(JsonObject args, String name, int fallback, int min, int max) {
        JsonElement value = args.get(name);
        if (value == null) return fallback;
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            int result = value.getAsBigDecimal().intValueExact();
            if (result < min || result > max) throw new ArithmeticException();
            return result;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(name + " 必须为 " + min + "～" + max + " 的整数");
        }
    }

    public static String coordinate(String value) {
        if (value == null) throw new IllegalArgumentException("缺少 GCJ-02 坐标");
        String[] parts = value.trim().split(",", -1);
        if (parts.length != 2) throw new IllegalArgumentException("坐标格式应为 经度,纬度（GCJ-02）");
        for (String part : parts) if (!part.trim().matches("-?\\d{1,3}(\\.\\d{1,6})?"))
            throw new IllegalArgumentException("坐标必须为数值且小数不超过 6 位");
        BigDecimal lon = new BigDecimal(parts[0].trim()), lat = new BigDecimal(parts[1].trim());
        if (lon.abs().compareTo(BigDecimal.valueOf(180)) > 0 || lat.abs().compareTo(BigDecimal.valueOf(90)) > 0)
            throw new IllegalArgumentException("经度必须在 -180～180，纬度必须在 -90～90");
        return lon.stripTrailingZeros().toPlainString() + "," + lat.stripTrailingZeros().toPlainString();
    }

    private static boolean validAdcode(String code) { return code != null && code.matches("\\d{6}"); }
    private static String districtLabel(District item) { return text(item.name) + "（adcode=" + text(item.adcode) + "，级别=" + text(item.level) + "，中心坐标=" + text(item.center) + "）"; }
    private static String geocodeLabel(Geocode item) { return text(item.formatted_address) + "；坐标=" + text(item.location) + "；adcode=" + text(item.adcode) + "；匹配级别=" + text(item.level); }
    private static String safeError(IOException error) { return error instanceof Client.QueryException ? error.getMessage() : "网络或响应异常"; }
    private static String unit(String value, String unit) { return value == null || value.isBlank() ? "未提供" : value + unit; }
    private static String text(String value) { return value == null || value.isBlank() ? "未提供" : value; }
    private static double number(String value) { try { return Double.parseDouble(value); } catch (RuntimeException e) { return 0; } }
    private static <T> List<T> items(List<T> list) { return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList(); }
    private static class Clarification extends Exception { Clarification(String message) { super(message); } }
}
