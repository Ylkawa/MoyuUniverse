package com.nekoyu.Universe.MinecraftConnect;

import com.google.gson.Gson;
import com.nekoyu.Universe.API.UniverseChannelMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@SuppressWarnings("unused")
public class MinecraftConnectSpigot extends JavaPlugin {
    WebSocketClient webSocketClient;
    Properties properties;
    URI uri;
    String token;
    String ID;

    @Override
    public void onEnable() {
        getLogger().info("正在加载配置");
        getDataFolder().mkdir();
        File configFile = new File("./plugins/Minecraft-Connect-Spigot/config.yml");
        if (configFile.isFile()) {
            try (FileReader fr = new FileReader(configFile)) {
                properties = new Yaml().loadAs(fr, Properties.class);
                uri = new URI(properties.getProperty("URI"));
                token = properties.getProperty("Token");
                ID = properties.getProperty("ThisID");
                newWebsocketClient();
                webSocketClient.connect();

                Bukkit.getScheduler().runTaskTimer(this, () -> {
                    if (webSocketClient.isClosed()) {
                        newWebsocketClient();
                        webSocketClient.connect();
                    }
                }, 100L, 100L); // 第一个参数是延迟时间（tick），第二个参数是周期时间（tick）
            } catch (URISyntaxException e) {
                getLogger().info("URI 格式有误");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else if (!configFile.exists()) {
            try {
                configFile.createNewFile();
                try (FileWriter fw = new FileWriter(configFile)) {
                    Properties newProp = new Properties();
                    newProp.put("URI", "");
                    newProp.put("Token", "");
                    newProp.put("ThisID", "");
                    fw.write(new Yaml().dump(newProp));
                    getLogger().info("没有检测到配置文件，已新建模板");
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    @Override
    public void onDisable() {

    }

    public void newWebsocketClient() {
        Map<String, String> header = new HashMap<>();
        header.put("Token", token);
        header.put("Type", "Spigot");
        header.put("ID", ID);
        webSocketClient = new WebSocketClient(uri, header) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                UniverseChannelMessage ucm = new UniverseChannelMessage();
                ucm.tag = "Universe";
                ucm.message = "RegisterListener";
                ucm.args.put("Tag", "Minecraft-Connect-Spigot");
                Gson gson = new Gson();
                webSocketClient.send(gson.toJson(ucm));
                ucm.args.replace("Tag", "Minecraft-Connect");
                webSocketClient.send(gson.toJson(ucm));
                ucm.args.replace("Tag", ID);
                webSocketClient.send(gson.toJson(ucm));
                getLogger().info("已与宇宙建立连结");
            }

            @Override
            public void onMessage(String s) {
                UniverseChannelMessage ucm = new Gson().fromJson(s, UniverseChannelMessage.class);
                Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ucm.args.get("sender") + "]: " + ucm.args.get("message"));
            }

            @Override
            public void onClose(int i, String s, boolean b) {

            }

            @Override
            public void onError(Exception e) {

            }
        };
    }
}
