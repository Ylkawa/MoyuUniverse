import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.net.MalformedURLException;
import java.net.URL;

public class RemoteWebDriverTest {
    public static void main(String[] args) throws MalformedURLException {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--start-maximized");

        WebDriver driver = new RemoteWebDriver(
                new URL("http://127.0.0.1:4444/wd/hub"),
                options
        );

        driver.get("https://www.baidu.com");

        driver.quit();
    }
}
