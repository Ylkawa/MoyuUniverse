package com.nekoyu;

import com.google.gson.Gson;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import org.checkerframework.checker.units.qual.g;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;

public class Listener {
    WebSocketClient webSocketClient;

    public Listener (WebSocketClient webSocketClient){
        this.webSocketClient = webSocketClient;
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onPlayerChat(PlayerChatEvent event) {
        // do something here
        System.out.println(event.getMessage());
    }

    @Subscribe
    public void onPlayerLogin(LoginEvent event) {
        if (webSocketClient.isOpen()) {
            Gson gson = new Gson();
            PlayerJoinEvent playerJoinEvent = new PlayerJoinEvent();
            playerJoinEvent.setPlayerName(event.getPlayer().getUsername());
            playerJoinEvent.setUuid(event.getPlayer().getUniqueId().toString());

            Event push = new Event();
            push.setType("PlayerJoinEvent");
            push.setBody(gson.toJsonTree(playerJoinEvent));

            webSocketClient.send(gson.toJson(push));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (webSocketClient.isOpen()) {
            Gson gson = new Gson();
            PlayerLeaveEvent playerLeaveEvent = new PlayerLeaveEvent();
            playerLeaveEvent.setPlayerName(event.getPlayer().getUsername());
            playerLeaveEvent.setUuid(event.getPlayer().getUniqueId().toString());

            Event push = new Event();
            push.setType("PlayerLeaveEvent");
            push.setBody(gson.toJsonTree(playerLeaveEvent));

            webSocketClient.send(gson.toJson(push));
        }
    }
}
