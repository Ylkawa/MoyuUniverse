import com.google.gson.Gson;
import com.nekoyu.Universe.AIChat.Web.MediaWiki.Client;

public class biligameWiki {
    public static void main(String[] args) {
        System.out.println(new Gson().toJson(Client.query("https://wiki.biligame.com/zzz/%E7%88%B1%E4%B8%BD%E4%B8%9D")));
    }
}
