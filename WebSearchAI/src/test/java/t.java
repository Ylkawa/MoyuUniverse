import com.google.gson.Gson;
import com.nekoyu.Universe.AIChat.WebSearch.BiliBiliAPI.DynamicList;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

public class t {
    public static void main(String[] args) throws FileNotFoundException {
        DynamicList dynamicList = new Gson().fromJson(new FileReader("C:\\Users\\imylk\\Desktop\\Projects\\space.json"), DynamicList.class);
        for (var i : dynamicList.data.items) {
            for (var d : i.modules) {
                if (d.module_author != null) System.out.println(d.module_author.user.name);
            }
        }
        System.out.println(dynamicList);
    }
}
