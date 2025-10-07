package com.nekoyu.Universe.AliyunWebhook;

import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.IOException;

public class AliyunWebhook extends Law {
    @Override
    public boolean prepare() {
        getConfigDir();
        File file = new File("./config/AliyunWebhook.yml");

        Universe.UniverseChannel.addHttpHandler("/AliyunWebhook/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                logger.debug(exchange.getRequestBody().toString());
            }
        });
        return true;
    }

    @Override
    public void run() {

    }

    @Override
    public void stop() {

    }
}
