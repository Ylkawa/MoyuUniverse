package com.nekoyu.Universe.AIChat.Web.BiliBiliAPI;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NavResponse {
    public int code;
    public String message;
    public int ttl;
    public Data data;

    public static class Data {
        public boolean isLogin;
        public Wbi_Img wbi_img;

        public static class Wbi_Img {
            String img_url;
            String sub_url;

            public String getImgFileName() {
                return extractKey(img_url);
            }

            public String getSubFileName() {
                return extractKey(sub_url);
            }

            private String extractKey(String url) {
                Pattern pattern = Pattern.compile("([a-f0-9]{32})(?=\\.png)");
                Matcher matcher = pattern.matcher(url);
                matcher.find();
                return matcher.group(1);
            }
        }
    }
}
