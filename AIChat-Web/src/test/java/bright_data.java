import com.google.gson.Gson;
import com.nekoyu.Universe.AIChat.Web.SearchAPI.BrightData.GoogleSearch.Client;
import com.nekoyu.Universe.AIChat.Web.SearchAPI.BrightData.GoogleSearch.SearchResponse;
import com.nekoyu.Universe.AIChat.Web.SearchAPI.SearchClient;
import com.nekoyu.Universe.AIChat.Web.SearchAPI.SearchResult;

import java.io.IOException;

public class bright_data {
    public static void main(String[] args) throws IOException {
        SearchClient searchClient = new Client("", "");
        SearchResult response = searchClient.search("绝区零");
        System.out.println(new Gson().toJson(response));
    }
}
