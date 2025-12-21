import com.google.gson.Gson;
import com.nekoyu.Universe.AIChat.WebSearch.BiliBiliAPI.Client;

import java.io.IOException;

public class bili {
    public static void main(String[] args) throws IOException {
        System.out.println(new Gson().toJson(Client.getVideoCommentList("BV1deScBQEz4")));
    }
}
