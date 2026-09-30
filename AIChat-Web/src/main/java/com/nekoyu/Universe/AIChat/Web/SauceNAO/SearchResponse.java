package com.nekoyu.Universe.AIChat.Web.SauceNAO;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SearchResponse {
    public Header header;
    public List<Result> results = new ArrayList<>();

    public static class Header {
        public Integer status;
        public String message;
        public String user_id;
        public String account_type;
        public int results_requested;
        public int results_returned;
        public Integer short_remaining;
        public Integer long_remaining;
        public int short_limit;
        public int long_limit;
        public double minimum_similarity;
        public Map<String, Index> index;
        public int search_depth;
    }

    public static class Index {
        public int status;
        public String parent_id;
        public String id;
        public int results;
    }

    public static class Result {
        public ResultHeader header;
        public Data data;
    }

    public static class ResultHeader {
        public double similarity;
        public String thumbnail;
        public int index_id;
        public String index_name;
        public int dupes;
        public int hidden;
    }

    /** 各来源索引共用的数据模型；不适用的字段保持 null。 */
    public static class Data {
        public List<String> ext_urls;
        public String title;
        public String source;
        public String member_name;
        public String member_id;
        public String pixiv_id;
        public String seiga_id;
        public String drawr_id;
        public String nijie_id;
        public String danbooru_id;
        public String gelbooru_id;
        public String yandere_id;
        public String konachan_id;
        public String sankaku_id;
        public String anidb_aid;
        public String mal_id;
        public String anilist_id;
        public String imdb_id;
        public String da_id;
        public String author_name;
        public String author_url;
        public String eng_name;
        public String jp_name;
        public String episode;
        public String part;
        public String est_time;
        @JsonAdapter(TextListAdapter.class)
        public List<String> creator;
        @JsonAdapter(TextListAdapter.class)
        public List<String> material;
        @JsonAdapter(TextListAdapter.class)
        public List<String> characters;
    }

    /** 部分索引使用字符串，部分索引使用字符串数组。 */
    public static class TextListAdapter extends TypeAdapter<List<String>> {
        @Override
        public List<String> read(JsonReader reader) throws IOException {
            List<String> values = new ArrayList<>();
            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                reader.beginArray();
                while (reader.hasNext()) {
                    if (reader.peek() == JsonToken.NULL) reader.nextNull();
                    else values.add(reader.nextString());
                }
                reader.endArray();
            } else if (reader.peek() == JsonToken.NULL) {
                reader.nextNull();
                return null;
            } else values.add(reader.nextString());
            return values;
        }

        @Override
        public void write(JsonWriter writer, List<String> values) throws IOException {
            if (values == null) { writer.nullValue(); return; }
            writer.beginArray();
            for (String value : values) writer.value(value);
            writer.endArray();
        }
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("图片来源查询结果\n");
        if (header != null) {
            if (header.status != null && header.status > 0) text.append("部分索引异常，结果可能不完整\n");
            if (header.message != null && !header.message.isBlank()) text.append(header.message).append('\n');
            text.append("剩余查询次数(30秒/24小时): ").append(header.short_remaining)
                    .append('/').append(header.long_remaining).append('\n');
        }
        if (results == null || results.isEmpty()) text.append("未找到匹配来源，无法确认\n");
        else for (Result result : results) {
            if (result == null || result.header == null) continue;
            text.append("\n{").append('\n').append("索引: ").append(result.header.index_name)
                    .append("\n相似度: ").append(result.header.similarity).append("%\n");
            Data data = result.data;
            if (data != null) {
                append(text, "标题", data.title);
                append(text, "作品/来源", data.source);
                append(text, "英文名", data.eng_name);
                append(text, "日文名", data.jp_name);
                append(text, "作者", data.member_name);
                append(text, "作者", data.author_name);
                append(text, "作者页面", data.author_url);
                append(text, "创作者", data.creator);
                append(text, "原作", data.material);
                append(text, "来源标注角色", data.characters);
                append(text, "集数", data.episode);
                append(text, "片段", data.part);
                append(text, "时间位置", data.est_time);
                append(text, "来源链接", data.ext_urls);
            }
            text.append("}\n");
        }
        return text.append("相似度仅表示来源候选，不能单独证明角色身份。").toString();
    }

    private static void append(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) text.append(label).append(": ").append(value).append('\n');
    }

    private static void append(StringBuilder text, String label, List<String> values) {
        if (values != null && !values.isEmpty()) append(text, label, String.join("、", values));
    }
}
