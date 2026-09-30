import com.google.gson.Gson;
import com.nekoyu.Universe.AIChat.Web.SauceNAO.SearchResponse;

/** 离线验证不同来源的字段类型和展示，不消耗 API 配额。 */
public class SauceNaoParseTest {
    public static void main(String[] args) {
        Gson gson = new Gson();
        SearchResponse response = gson.fromJson("""
                {"header":{"status":1,"short_remaining":3,"long_remaining":99},"results":[
                  {"header":{"similarity":"92.5","index_name":"Pixiv","index_id":5},
                   "data":{"title":"作品标题","pixiv_id":12345,"member_name":"作者",
                           "ext_urls":["https://example.com/art/12345"]}},
                  {"header":{"similarity":"81.2","index_name":"Danbooru"},
                   "data":{"creator":["作者A","作者B"],"material":"原作",
                           "characters":["角色标注"]}},
                  {"header":{"similarity":"80","index_name":"Anime"},
                   "data":{"source":"动画","creator":"制作方","episode":"3","est_time":"00:12:34"}}
                ]}
                """, SearchResponse.class);
        check(response.header.status == 1, "部分失败状态");
        check(response.results.get(0).header.similarity == 92.5, "数字字符串相似度");
        check("12345".equals(response.results.get(0).data.pixiv_id), "数字来源 ID");
        check(response.results.get(1).data.creator.size() == 2, "作者数组");
        check("原作".equals(response.results.get(1).data.material.get(0)), "字符串原作");
        check("制作方".equals(response.results.get(2).data.creator.get(0)), "字符串作者");
        String output = response.toString();
        check(output.contains("部分索引异常") && output.contains("作品标题")
                && output.contains("https://example.com/art/12345") && output.contains("00:12:34"), "来源摘要");
        SearchResponse empty = gson.fromJson("{\"header\":{\"status\":0},\"results\":[]}", SearchResponse.class);
        check(empty.toString().contains("未找到匹配来源"), "空结果");
        System.out.println("SauceNaoParseTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
