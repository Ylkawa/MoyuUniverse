import com.google.gson.*;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class solve_qzone {
    private static final Pattern HEX_ESCAPE = Pattern.compile("\\\\x([0-9A-Fa-f]{2})");

    public static String cleanToGsonJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "{}";
        }

        String s = raw.replace("\uFEFF", ""); // 去 BOM

        // 1) \xNN -> \\u00NN
        s = hexToUnicodeEscapes(s);

        // 2) 规范一些裸值
        // 说明：这是“够用型”处理，适合你这种脏接口响应
        s = s.replaceAll("\\bundefined\\b", "null")
                .replaceAll("\\bNaN\\b", "null")
                .replaceAll("\\bInfinity\\b", "null")
                .replaceAll("\\b-Infinity\\b", "null")
                .replaceAll("\\bTrue\\b", "true")
                .replaceAll("\\bFalse\\b", "false")
                .replaceAll("\\bNone\\b", "null");

        // 3) 去尾逗号
        s = s.replaceAll(",(?=\\s*[}\\]])", "");

        // 4) 尝试宽松解析，再输出严格 JSON
        try {
            JsonReader reader = new JsonReader(new StringReader(s));
            reader.setLenient(true);

            JsonElement element = JsonParser.parseReader(reader);

            Gson gson = new GsonBuilder()
                    .disableHtmlEscaping()
                    .create();

            return gson.toJson(element);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("清洗后仍无法被 Gson 解析: " + e.getMessage(), e);
        }
    }

    private static String hexToUnicodeEscapes(String input) {
        Matcher m = HEX_ESCAPE.matcher(input);
        StringBuilder sb = new StringBuilder(input.length());
        while (m.find()) {
            // \x3C -> \u003C
            m.appendReplacement(sb, "\\\\u00" + m.group(1).toUpperCase());
        }
        m.appendTail(sb);
        return sb.toString();
    }


    public static void main(String[] args) {
        try {
            String rawContent = new String(Files.readAllBytes(Paths.get("C:\\Users\\imylk\\Desktop\\raw_resp.txt")), "UTF-8");

            String cleaned = cleanToGsonJson(rawContent);

            System.out.println(cleaned);

            Gson gson = new Gson();
            JsonElement element = gson.fromJson(cleaned, JsonElement.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}