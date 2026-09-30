import com.nekoyu.Universe.AIChat.Web.SauceNAO.Client;
import com.nekoyu.Universe.AIChat.Web.SauceNAO.SearchResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.Scanner;

/** 搜图验证入口：运行 main，输入图片 URL，打印搜索结果。 */
public class SauceNaoDemo {
    public static void main(String[] args) throws IOException {
        try (Scanner input = new Scanner(System.in)) {
            String apiKey = System.getenv("SAUCENAO_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                System.out.print("请输入 SauceNAO API Key: ");
                apiKey = input.nextLine().trim();
            }

            String imageUrl;
            if (args.length >= 1) {
                imageUrl = args[0];
            } else {
                System.out.print("请输入图片 URL: ");
                imageUrl = input.nextLine().trim();
            }

            String proxyUrl = args.length >= 2 ? args[1] : System.getenv("HTTPS_PROXY");
            if (proxyUrl == null || proxyUrl.isBlank()) proxyUrl = System.getenv("HTTP_PROXY");
            Proxy proxy = parseProxy(proxyUrl);
            System.out.println("请求代理: " + (proxy == null ? "JVM 默认代理选择器" : proxy));
            Client client = new Client(apiKey, proxy);
            SearchResponse response = client.search(imageUrl);
            System.out.println(response);
        }
    }

    static Proxy parseProxy(String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) return null;
        if ("direct".equalsIgnoreCase(proxyUrl)) return Proxy.NO_PROXY;
        URI uri = URI.create(proxyUrl);
        Proxy.Type type;
        if ("http".equalsIgnoreCase(uri.getScheme())) type = Proxy.Type.HTTP;
        else if ("socks5".equalsIgnoreCase(uri.getScheme())) type = Proxy.Type.SOCKS;
        else throw new IllegalArgumentException("代理地址只支持 http:// 或 socks5://，或 direct 直连");
        if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())))
            throw new IllegalArgumentException("代理地址应为 协议://主机:端口");
        return new Proxy(type, new InetSocketAddress(uri.getHost(), uri.getPort()));
    }
}
