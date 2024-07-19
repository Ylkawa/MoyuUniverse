package com.nekoyu;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.bukkit.scheduler.BukkitRunnable;


import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static java.util.Spliterators.iterator;
import static org.bukkit.Bukkit.getOnlinePlayers;

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
        }, 0L, 200L); // 第一个参数是延迟时间（tick），第二个参数是周期时间（tick）
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        wsc.close();
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event){
        ArrayList<String> playerList = new ArrayList<>();
        Collection<? extends Player> collection = getOnlinePlayers();
        Map<String, Object> request = new HashMap<>();
        for (Player player : collection) {
            playerList.add(player.getName());
        }
        request.put("event", "PlayerJoinEvent");
        request.put("PlayerName", event.getPlayer().getName());
        request.put("PlayerList", playerList);

        String json = new Gson().toJson(request);
        getLogger().info(json);
        wsc.send(json);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event){
        ArrayList<String> playerList = new ArrayList<>();
        Map<String, Object> request = new HashMap<>();
        for (Player player : getOnlinePlayers()) {
            playerList.add(player.getName());
        }
        request.put("event", "PlayerQuitEvent");
        request.put("PlayerName", event.getPlayer().getName());
        request.put("PlayerList", playerList);

        String json = new Gson().toJson(request);
        getLogger().info(json);
        wsc.send(json);
    }

    public void wsConn(){
        try {
            wsc = new WebSocketClient(new URI("ws://localhost:24430")) {
                @Override
                public void onOpen(ServerHandshake serverHandshake) {
                    wsc.send("Connect: planet");
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
            e.getCause().printStackTrace();
        }
    }

}