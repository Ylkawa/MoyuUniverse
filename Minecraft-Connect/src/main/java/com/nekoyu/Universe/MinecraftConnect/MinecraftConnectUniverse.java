package com.nekoyu.Universe.MinecraftConnect;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.nekoyu.Universe.API.*;
import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageChannel;
import com.nekoyu.Universe.API.MessageChannel.MessageChannelListener;
import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;
import org.yaml.snakeyaml.Yaml;

import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

public class MinecraftConnectUniverse extends Law implements UniverseListener {
    Multimap<String, String> forwardingStructureToChannel = ArrayListMultimap.create();
    Multimap<String, String> forwardingStructureToServer = ArrayListMultimap.create();
    @Override
    public boolean prepare() {
        File cfgDic = new File("./config/Minecraft-Connect/");
        if (cfgDic.mkdir()) logger.info("配置文件创建中");
        File config = new File("./config/Minecraft-Connect/config.yml");
        if (!config.exists()) {
            try (FileWriter fw = new FileWriter(config)) {
                Map<String, Object> content = new HashMap<>();
                content.put("Forward", new ArrayList<String>());
                fw.write(new Yaml().dump(content));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        try (FileReader fr = new FileReader("./config/Minecraft-Connect/config.yml")) {
            Map<String, Object> content = new Yaml().loadAs(fr, HashMap.class);
            List<String> forward = (List<String>) content.get("Forward");
            if (forward == null || forward.isEmpty()) {
                logger.error("配置文件中 Forward 未定义，无法继续加载");
                return false;
            }
            // 建立转发结构映射
            for (String value : forward) {
                if (Pattern.matches("^[a-zA-Z0-9\\-]+->[a-zA-Z0-9\\-]+:[a-zA-Z0-9/\\-]+$", value)) {
                    String[] args = value.split("->");
                    String[] arg1 = args[1].split(":");
                    forwardingStructureToChannel.put(args[0].strip(), arg1[0].strip() + ":" + arg1[1].strip());
                } else if (Pattern.matches("^[a-zA-Z0-9\\-]+:[a-zA-Z0-9/\\-]+->[a-zA-Z0-9\\-]+$", value)) {
                    String[] args = value.split("->");
                    String[] arg1 = args[0].split(":");
                    forwardingStructureToServer.put(arg1[0].strip() + ":" + arg1[1].strip(), args[1].strip());
                } else {
                    logger.warn("""
                            {} 行的定义有误，正确示例：
                            [ServerID] -> [MessageChannelID]:[SessionID]
                            [MessageChannelID]:[SessionID] -> [ServerID]""", value);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    public void run() {
        UniverseChannel.registerListener("Minecraft-Connect", this);
        for (Map.Entry<String, String> entry : forwardingStructureToServer.entries()) {
            String target = entry.getValue();
            Universe.MessageChannelManager.listenToSession(entry.getKey(), mcm -> {
                ForwardChat ucm = new ForwardChat();
                ucm.message = "ForwardChat";
                ucm.MCMsg = mcm;
                Color color = Color.CYAN;
                if (mcm.sender.getAvatar() != null) color = mcm.sender.getAvatar().getMainColor();
                ucm.RGB = new int[]{color.getRed(), color.getGreen(), color.getBlue()};

                UniverseChannel.broadcast(target, ucm);
            });
        }
    }

    @Override
    public void stop() {
        UniverseChannel.unRegisterListener("Minecraft-Connect", this);
    }

    @Override
    public void onMessage(Planet planet, String message, Map args, String rawJson) {
        Collection<String> defineOfForward = forwardingStructureToChannel.get(planet.getID());
        switch (message) {
            case "InfoUpload" -> {
                logger.info("{} 的构建版本为: {}", planet.getID(), String.valueOf(args.get("BuildVersion")));
                PlaceHolder.setReplacement("Version:" + planet.getID(), String.valueOf(args.get("BuildVersion")));
            }
            case "StatusUpload" -> {
                switch (planet.getType()) {
                    case "Velocity":
                        // 把在线人数添加到 PlaceHolder
                        PlaceHolder.setReplacement("Online:" + planet.getID(), String.valueOf(((ArrayList<String>) args.get("Players")).size()));
                }
            }
            case "player_join_game" -> {
                if (defineOfForward.isEmpty()) {
                    logger.warn("没有为 {} 定义有效的转发规则", planet.getID());
                }
                for (String value : defineOfForward) {
                    Universe.MessageChannelManager.sendMessage(value, args.get("Joiner") + " 加入了服务器");
                }
            }
            case "player_leave_game" -> {
                if (defineOfForward.isEmpty()) {
                    logger.warn("没有为 {} 定义有效的转发规则", planet.getID());
                }
                for (String value : defineOfForward) {
                    Universe.MessageChannelManager.sendMessage(value, args.get("Leaver") + " 退出了服务器");
                }
            }
        }
    }
}