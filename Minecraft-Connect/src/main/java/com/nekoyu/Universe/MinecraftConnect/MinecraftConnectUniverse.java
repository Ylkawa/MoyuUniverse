package com.nekoyu.Universe.MinecraftConnect;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageChannel;
import com.nekoyu.Universe.API.MessageChannel.MessageChannelListener;
import com.nekoyu.Universe.API.Planet;
import com.nekoyu.Universe.API.UniverseChannelMessage;
import com.nekoyu.Universe.API.UniverseListener;
import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
public class MinecraftConnectUniverse extends Law implements UniverseListener {
    Multimap<String, String> forwardingStructureToChannel = ArrayListMultimap.create();
    Multimap<String, String> forwardingStructureToServer = ArrayListMultimap.create();
    @Override
    public boolean prepare() {
        File cfgDic = new File("./config/Minecraft-Connect/");
        if (!cfgDic.exists()) cfgDic.mkdir();
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
                if (Pattern.matches("[a-zA-Z0-9]+->[a-zA-Z0-9]+:[a-zA-Z0-9/]+", value)) {
                    String[] args = value.split("->");
                    String[] arg1 = args[1].split(":");
                    forwardingStructureToChannel.put(args[0].strip(), arg1[0].strip() + ":" + arg1[1].strip());
                } else if (Pattern.matches("[a-zA-Z0-9]+:[a-zA-Z0-9/]+->[a-zA-Z0-9]+", value)) {
                    String[] args = value.split("->");
                    String[] arg1 = args[0].split(":");
                    forwardingStructureToServer.put(arg1[0].strip() + ":" + arg1[1].strip(), args[1].strip());
                } else {
                    logger.warn("{} 行的定义有误，正确示例：\n" +
                            "[ServerID] -> [MessageChannelID]:[SessionID]\n" +
                            "[MessageChannelID]:[SessionID] -> [ServerID]", value);
                }
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    public void run() {
        Universe.UniverseChannel.registerListener("Minecraft-Connect", this);
        for (Map.Entry<String, String> entry : forwardingStructureToServer.entries()) {
            Universe.MessageChannelManager.listenToSession(entry.getKey(), new MessageChannelListener() {
                String target = entry.getValue();
                @Override
                public void onMessage(MCMessage mcm) {
                    UniverseChannelMessage ucm = new UniverseChannelMessage();
                    ucm.message = "ForwardChat";
                    ucm.args.put("sender", mcm.sender.getNickname());
                    ucm.args.put("message", mcm.message);

                    Universe.UniverseChannel.broadcast(target, ucm);
                }
            });
        }
    }

    @Override
    public void stop() {
        Universe.UniverseChannel.unRegisterListener("Minecraft-Connect", this);
    }

    @Override
    public void onMessage(Planet planet, String message, Map args) {
        Collection<String> defineOfForward = forwardingStructureToChannel.get(planet.getID());
        switch (message) {
            case "StatusUpload":
                switch (planet.getType()) {
                    case "":
                }
            case "player_join_game":
                if (defineOfForward.isEmpty()) {
                    logger.warn("没有为 {} 定义有效的转发规则", planet.getID());
                }
                for (String value : defineOfForward) {
                    String[] target = value.split(":");
                    MessageChannel mc = Universe.MessageChannelManager.getChannel(target[0]);
                    if (mc == null) {
                        logger.warn("为 {} 定义的消息通道不存在，转发失败", planet.getID());
                        return;
                    }
                    mc.sendMessage(target[1], args.get("Joiner") + " 加入了服务器");
                }
                break;
            case "player_leave_game":
                if (defineOfForward.isEmpty()) {
                    logger.warn("没有为 {} 定义有效的转发规则", planet.getID());
                }
                for (String value : defineOfForward) {
                    String[] target = value.split(":");
                    MessageChannel mc = Universe.MessageChannelManager.getChannel(target[0]);
                    if (mc == null) {
                        logger.warn("为 {} 定义的消息通道不存在，转发失败", planet.getID());
                        return;
                    }
                    mc.sendMessage(target[1], args.get("Leaver") + " 退出了服务器");
                }
                break;
        }
    }
}