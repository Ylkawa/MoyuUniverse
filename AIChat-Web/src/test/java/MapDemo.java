import com.google.gson.JsonParser;
import com.nekoyu.Universe.AIChat.Web.Amap.Client;
import com.nekoyu.Universe.AIChat.Web.Amap.MapService;

import java.net.Proxy;
import java.util.Scanner;

/** IDE main: AMAP_API_KEY + one MapQuery JSON argument, or interactive JSON input. */
public class MapDemo {
    public static void main(String[] args) {
        String key = System.getenv("AMAP_API_KEY");
        if (key == null || key.isBlank()) {
            System.out.println("请在 IDE 运行配置中设置 AMAP_API_KEY（Web 服务 API 类型）。");
            return;
        }
        String json;
        if (args.length > 0) json = args[0];
        else {
            System.out.println("输入 MapQuery JSON，例如 {\"action\":\"weather\",\"location\":\"杭州市\",\"type\":\"both\"}");
            json = new Scanner(System.in).nextLine();
        }
        try {
            System.out.println(new MapService(new Client(key, Proxy.NO_PROXY, 60)).query(JsonParser.parseString(json)));
        } catch (RuntimeException e) {
            System.out.println("参数错误：请提供有效的 MapQuery JSON 对象。");
        }
    }
}
