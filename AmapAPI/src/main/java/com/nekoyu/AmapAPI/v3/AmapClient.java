package com.nekoyu.AmapAPI.v3;

import com.google.gson.Gson;
import com.nekoyu.AmapAPI.v3.geocode.RegeoRequest;
import com.nekoyu.AmapAPI.v3.geocode.RegeoResponse;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Map;

public class AmapClient {
    String key;
    OkHttpClient httpClient;
    Gson gson;

    public AmapClient(String key) {
        this.key = key;
        httpClient = new OkHttpClient();
        gson = new Gson();
    }

    public RegeoResponse regeoRequest(RegeoRequest req) throws IOException, AmapException {
        StringBuilder url = new StringBuilder().append("https://restapi.amap.com/v3/geocode/regeo?").append("Key=").append(key);
        for (Map.Entry<String, String> entry : req.args.entrySet()) {
            url.append("&").append(entry.getKey()).append("=").append(entry.getValue());
        }
        Request request = new Request.Builder()
                .url(url.toString())
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String rawResponse = response.body().string();
            if (response.code() == 200) {
                System.out.println(rawResponse);
                // 由于缺德地图的API响应体中，空的值不是null，而是一个空的数组？？？所以解析的时候，空数组不能被解析成对象，代码不兼容（不是，这么写报复社会来的吧……），我没辙，把所有空数组转换成null，应该能规避这个特性吧
                RegeoResponse regeoResponse = gson.fromJson(rawResponse.replaceAll("\\[\\]", "null"), RegeoResponse.class);
                if (regeoResponse.info.equals("OK")) return regeoResponse;
                var amapException = new AmapException(regeoResponse.info);
                amapException.rawResponse = rawResponse;
                throw amapException;
            } else {
                AmapException amapException = new AmapException("Unknown error");
                amapException.rawResponse = rawResponse;
                throw amapException;
            }
        }
    }
}
