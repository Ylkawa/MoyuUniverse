import java.net.InetSocketAddress;
import java.net.Proxy;

/** 离线检查验证类的代理解析，不发网络请求。 */
public class SauceNaoProxyTest {
    public static void main(String[] args) {
        Proxy http = SauceNaoDemo.parseProxy("http://127.0.0.1:10808");
        check(http.type() == Proxy.Type.HTTP, "HTTP 代理类型");
        check(((InetSocketAddress) http.address()).getPort() == 10808, "HTTP 代理端口");
        check(SauceNaoDemo.parseProxy("socks5://127.0.0.1:10808").type() == Proxy.Type.SOCKS, "SOCKS 代理");
        check(SauceNaoDemo.parseProxy("direct") == Proxy.NO_PROXY, "显式直连");
        check(SauceNaoDemo.parseProxy(null) == null, "保留 JVM 默认代理选择器");
        check(SauceNaoDemo.parseProxy("") == null, "空配置");
        for (String invalid : new String[]{"http://127.0.0.1", "https://127.0.0.1:10808",
                "http://127.0.0.1:99999", "http://user:password@127.0.0.1:10808"}) {
            try {
                SauceNaoDemo.parseProxy(invalid);
                throw new AssertionError("应拒绝无效代理配置");
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
        System.out.println("SauceNaoProxyTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
