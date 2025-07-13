package com.nekoyu.Universe.MinecraftConnect;

import com.google.gson.Gson;
import com.nekoyu.Universe.API.UCMessage;
import com.velocitypowered.api.event.Subscribe;
import com.google.inject.Inject;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Plugin(id = "minecraftconnectvelocity", name = "Minecraft Connect - Moyu Universe", version = "0.1.0-SNAPSHOT",
        url = "https://nekoyu.com", description = "A connector to Moyu Universe", authors = {"Huanyue Moyu", "imylk"})
public class MinecraftConnectVelocity {
    private String ID;
    @Inject
    private final ProxyServer velocity;
    private final Logger logger;
    private WebSocketClient wsClient;
    URI universeURI;
    private String universeToken;
    private boolean loadAble = true;

    @Inject
    public MinecraftConnectVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.velocity = server;
        this.logger = logger;

        logger.info(">-- 末屿宇宙 --< : Loading Connection");

        // 保证工作目录存在
        File workDic = new File("./plugins/Minecraft-Connect-Velocity");
        if (!workDic.exists()) workDic.mkdir();

        // 读取配置文件
        try (BufferedReader reader = new BufferedReader(new FileReader("./plugins/Minecraft-Connect-Velocity/config.yml"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
                sb.append("\n");
            }
            try {
                Map config = new Yaml().loadAs(sb.toString(), HashMap.class);
                if (config.get("Token") != null && !config.get("Token").toString().isEmpty()) {
                    this.universeToken = config.get("Token").toString();
                } else {
                    logger.error("配置中 Token 设置有误");
                    loadAble = false;
                }
                if (config.get("URI") != null && !config.get("URI").toString().isEmpty()) {
                    try {
                        this.universeURI = new URI(config.get("URI").toString());
                    } catch (URISyntaxException e) {
                        logger.error("URI 格式错误，请修复");
                        loadAble = false;
                    }
                } else {
                    logger.error("URI 没有定义，请修复");
                    loadAble = false;
                }
                if (config.get("ThisID") != null && !config.get("ThisID").toString().isEmpty()) {
                    this.ID = config.get("ThisID").toString();
                } else {
                    logger.error("请定义本服务端的ID");
                }
            } catch (YAMLException e) {
                logger.error("配置文件格式错误，请修复");
                logger.error("无法对接宇宙");
            }
        } catch (FileNotFoundException e) {
            loadAble = false;
            logger.info("正在尝试新建配置文件");
            String content =
                    """
                            URI:\s
                            Token:\s
                            ThisID:\s""";
            try (FileWriter fw = new FileWriter("./plugins/Minecraft-Connect-Velocity/config.yml")) {
                fw.write(content);
            } catch (IOException ex) {
                logger.error("无法创建配置文件");
                throw new RuntimeException(ex);
            }
        } catch (IOException e) {
            loadAble = false;
            throw new RuntimeException(e);
        }
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        if (!loadAble) return;
        logger.info("创建穿隧...");
        newWebsocketClient();
        wsClient.connect();

        // 连接保活
        velocity.getScheduler().buildTask(this, () -> {
                    // 这里是你要循环执行的代码
                    if (wsClient.isClosed()){
                        newWebsocketClient();
                        wsClient.connect();
                    }
                })
                .delay(10, TimeUnit.SECONDS) // 延迟1秒后第一次执行
                .repeat(5, TimeUnit.SECONDS) // 每5秒执行一次
                .schedule();
    }

    public void newWebsocketClient() {
        Map<String, String> header = new HashMap<>();
        header.put("Token", universeToken);
        header.put("Type", "Velocity");
        header.put("ID", ID);
        wsClient = new WebSocketClient(universeURI, header) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                logger.info("已与宇宙建立连结");
            }

            @Override
            public void onMessage(String s) {

            }

            @Override
            public void onClose(int i, String s, boolean b) {
                logger.warn("与宇宙的连接断开");
            }

            @Override
            public void onError(Exception e) {

            }
        };
    }

    @Subscribe
    public void onPlayerLogin(LoginEvent event) {
        Map<String, String> messageBody = new HashMap<>();
        messageBody.put("Joiner", event.getPlayer().getUsername());

        UCMessage ucm = new UCMessage();
        ucm.tag = "Minecraft-Connect";
        ucm.args = messageBody;
        ucm.message = "player_join_game";

        wsClient.send(new Gson().toJson(ucm));
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Map<String, String> messageBody = new HashMap<>();
        messageBody.put("Leaver", event.getPlayer().getUsername());

        UCMessage ucm = new UCMessage();
        ucm.tag = "Minecraft-Connect";
        ucm.args = messageBody;
        ucm.message = "player_leave_game";

        wsClient.send(new Gson().toJson(ucm));
    }
}
