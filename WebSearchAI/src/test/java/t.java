import com.google.gson.Gson;
import com.nekoyu.Universe.AIChat.WebSearch.BiliBiliAPI.CommentList;
import com.nekoyu.Universe.AIChat.WebSearch.BiliBiliAPI.DynamicList;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class t {
    public static void main(String[] args) {
        var logger = System.out;
        Request request = new Request.Builder()
                .url("https://api.bilibili.com/x/v2/reply?type=1&oid=748099112&ps=20&pn=1")
                .get()
                .addHeader("Cookie", "buvid3=2E1EED65-11F9-4658-17EA-0DB9E929D66152367infoc; b_nut=1759726452; _uuid=6499C565-1872-DB23-B577-546ABCB463DE54320infoc; enable_web_push=DISABLE; buvid4=7853FCD0-5102-80DC-98B5-49CB3232892C61111-025100612-4Q3BNoQjbZZf6B1W3vN5cQ%3D%3D; SESSDATA=dbd19926%2C1775278516%2C42294%2Aa1CjDb5CJoCVGkb2eG8xyAERNGKAHTLO2h30RFCjaSdniJZmdJ7Gg5J5frhvi31Rv6ESESVl9paG81VWdZRmh3MXhueW41cHpOc29TdzE2eUdCUHpfTHNsWkFpTVNveDdpTU9hWmg1TnNlLVFmWjZtM2RKbWE0eHJjQ2VMRnJMRkNTQUxKdlZZNTBnIIEC; bili_jct=cd99933d11011f73ad815b34c87d861d; DedeUserID=497423225; DedeUserID__ckMd5=03db07df58e11725; sid=4v6eb0b9; theme-tip-show=SHOWED; rpdid=|(k|k)kJlJkY0J'u~lm~kYlJ); theme-avatar-tip-show=SHOWED; CURRENT_QUALITY=120; fingerprint=b7eadede72d08601230a648d0f15324b; buvid_fp_plain=undefined; buvid_fp=b7eadede72d08601230a648d0f15324b; CURRENT_LANGUAGE=; bili_ticket=eyJhbGciOiJIUzI1NiIsImtpZCI6InMwMyIsInR5cCI6IkpXVCJ9.eyJleHAiOjE3NjEyMjU3MDgsImlhdCI6MTc2MDk2NjQ0OCwicGx0IjotMX0.aXg33-xTWSKVc2QYhm-kG_Zv0dxKgiq4WvaXlYksCc0; bili_ticket_expires=1761225648; bp_t_offset_497423225=1126401632037240832; bmg_af_switch=1; bmg_src_def_domain=i0.hdslb.com; b_lsid=A107B99F6_19A0B6BAD74; home_feed_column=5; browser_resolution=1707-932; CURRENT_FNVAL=2000") // 游客的cookie
                .addHeader("priority", "u=0, i")
                .addHeader("sec-ch-ua", "\"Google Chrome\";v=\"141\", \"Not?A_Brand\";v=\"8\", \"Chromium\";v=\"141\"")
                .addHeader("sec-ch-ua-mobile", "?0")
                .addHeader("sec-ch-ua-platform", "\"Windows\"")
                .addHeader("sec-fetch-dest", "empty")
                .addHeader("sec-fetch-mode", "cors")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://www.bilibili.com/")
                .addHeader("Origin", "https://www.bilibili.com")
                .build();
        try (Response resp = new OkHttpClient().newCall(request).execute()) {
            String respBody = resp.body().string();
            System.out.println(respBody);
            FileWriter fw = new FileWriter("C:\\Users\\imylk\\Desktop\\Projects\\comment.json");
            fw.write(respBody);
            fw.close();
            CommentList cl = new Gson().fromJson(respBody, CommentList.class);
            for (var reply : cl.data.replies) {
                logger.println(reply.member.uname + ": " + reply.content.message);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
