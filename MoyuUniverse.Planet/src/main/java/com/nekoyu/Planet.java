package com.nekoyu;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.bukkit.scheduler.BukkitRunnable;


import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import static java.util.Spliterators.iterator;
import static org.bukkit.Bukkit.getOnlinePlayers;
import static org.bukkit.Bukkit.getTPS;

public final class Planet extends JavaPlugin implements Listener {

    WebSocketClient wsc;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        // Plugin startup logic
        getLogger().info("Welcome to Moyu Universe.");
        getLogger().info("Connecting to nebula.");
        wsConn();

        new BukkitRunnable() {
            public void run(){
                wsc.connect();
            }
        }.runTaskAsynchronously(this);

        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                if (Boolean.TRUE.equals(wsc.isClosed())) {
                    wsConn();
                    wsc.connect();
                }
            }
        }, 5L, 200L); // 第一个参数是延迟时间（tick），第二个参数是周期时间（tick）
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        wsc.close();
    }

    public void wsConn(){
        try {
            wsc = new WebSocketClient(new URI("ws://localhost:24430")) {
                @Override
                public void onOpen(ServerHandshake serverHandshake) {
                    
                }

                @Override
                public void onMessage(String s) {
                    //Map map = new Gson().fromJson(s, HashMap.class);

                }

                @Override
                public void onClose(int i, String s, boolean b) {
                    getLogger().info("WebSocketClosed: " + i + " " + s + " " + b);
                }

                @Override
                public void onError(Exception e) {

                }
            };
        } catch (URISyntaxException e) {
            System.out.println(e.getMessage());
        }
    }

}