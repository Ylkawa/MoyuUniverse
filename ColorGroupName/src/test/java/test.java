import com.google.gson.Gson;
import com.nekoyu.Universe.ColorGroupName.Config;

import java.util.HashMap;

public class test {
    public static void main(String[] args) {
        Config cfg = new Config();
        Config.Set value = new Config.Set();
        value.delay = 60;
        value.template = "";
        cfg.Settings.put("lingyu:group/12341234", value);
        cfg.Settings.put("lingyu:private/123412344", value);

        System.out.println(new Gson().toJson(cfg));
    }
}
