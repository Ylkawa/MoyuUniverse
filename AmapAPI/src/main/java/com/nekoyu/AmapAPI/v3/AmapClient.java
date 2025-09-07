package com.nekoyu.AmapAPI.v3;

import com.google.gson.Gson;
import com.nekoyu.AmapAPI.v3.geocode.regeoRequest;
import com.nekoyu.AmapAPI.v3.geocode.regeoResponse;
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

    public regeoResponse regeoRequest(regeoRequest req) throws IOException, AmapException {
        StringBuilder url = new StringBuilder().append("https://restapi.amap.com/v3/geocode/regeo?").append("Key=").append(key);
        for (Map.Entry<String, String> entry : req.args.entrySet()) {
            url.append("&").append(entry.getKey()).append("=").append(entry.getValue());
        }
        Request request = new Request.Builder()
                .url("https://restapi.amap.com/v3/geocode/regeo")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() == 200) {
                return gson.fromJson(response.body().string(), regeoResponse.class);
            } else {
                AmapException amapException = new AmapException("Unknown error");
                amapException.rawResponse = response.body().string();
                throw amapException;
            }
        }
    }
}
