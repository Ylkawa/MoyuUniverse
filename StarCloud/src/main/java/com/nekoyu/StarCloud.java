package com.nekoyu;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.nekoyu.Listener;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

@Plugin(
        id = "moyuuniverse", name = "MoyuUniverse", version = "1.0.0-Snapshot", authors = {"Nekoyu", "HatsuneYLK", "Lonely_Melody"}, description = "", url = "https://www.nekoyu.com/"
)
public class StarCloud {
    @Inject
    private final ProxyServer proxy;
    private Logger logger;
    WebSocketClient webSocketClient = newWebSocketClient();

    @Inject
    public StarCloud(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory){
        this.proxy = server;
        this.logger = logger;

        logger.info("末屿宇宙：星云");
    }

    private void uploadStatus() {
        if (webSocketClient.isOpen()){
            Gson gson = new Gson();
            StarCloudStatus starCloudStatus = new StarCloudStatus();
            starCloudStatus.setNumOfPlayer(proxy.getPlayerCount());

            Event event = new Event();
            event.setVersion(1);
            event.setType("StarCloudStatusUpload");
            event.setBody(gson.toJsonTree(starCloudStatus));
            webSocketClient.send(gson.toJson(event));
        }
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Do some operation demanding access to the Velocity API here.
        // For instance, we could register an event:
        if (webSocketClient != null) {
            webSocketClient.connect();
        }
        proxy.getEventManager().register(this, new Listener(webSocketClient));

        proxy.getScheduler().buildTask(this, () -> {
                    // 这里是你要循环执行的代码
                    if (webSocketClient.isClosed()){
                        webSocketClient = newWebSocketClient();
                    }
                    uploadStatus();
                })
                .delay(10, TimeUnit.SECONDS) // 延迟1秒后第一次执行
                .repeat(5, TimeUnit.SECONDS) // 每5秒执行一次
                .schedule();
    }

    private WebSocketClient newWebSocketClient() {
        try {
            return new WebSocketClient(new URI("ws://localhost:24430")) {
                @Override
                public void onOpen(ServerHandshake serverHandshake) {
                    System.out.println("byd");
                }

                @Override
                public void onMessage(String s) {

                }

                @Override
                public void onClose(int i, String s, boolean b) {
                    logger.info("WebSocketClosed: " + i + " " + s + " " + b);
                }

                @Override
                public void onError(Exception e) {

                }
            };
        } catch (URISyntaxException exception){
            logger.warning(exception.getMessage());
        }
        return null;
    }
}