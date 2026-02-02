package com.nekoyu.Universe.MinecraftConnect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.typeadapters.RuntimeTypeAdapterFactory;
import com.nekoyu.Universe.API.MessageChannel.MessageField.*;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.UniverseChannelMessage;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.yaml.snakeyaml.Yaml;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unused")
public class MinecraftConnectSpigot extends JavaPlugin {
    public static Gson gson;
    WebSocketClient webSocketClient;
    Properties properties;
    URI uri;
    String token;
    String ID;

    @Override
    public void onEnable() {
        RuntimeTypeAdapterFactory<MsgField> factory =
                RuntimeTypeAdapterFactory.of(MsgField.class, "type")
                        .registerSubtype(TextField.class, "text")
                        .registerSubtype(ImageField.class, "image")
                        .registerSubtype(VideoField.class, "video")
                        .registerSubtype(VoiceField.class, "voice")
                        .registerSubtype(FileField.class, "file")
                        .registerSubtype(LocationField.class, "location")
                        .registerSubtype(ShareUriField.class, "shareUri")
                        .registerSubtype(ShareContactField.class, "shareContact")
                        .registerSubtype(MetaField.class, "meta")
                        .registerSubtype(AtField.class, "at");

        gson = new GsonBuilder()
                .registerTypeAdapterFactory(factory)
                .create();
        getLogger().info("正在加载配置");
        if (getDataFolder().mkdir()) getLogger().info("创建配置文件...");
        File configFile = new File("./plugins/Minecraft-Connect-Spigot/config.yml");
        if (configFile.isFile()) {
            try (FileReader fr = new FileReader(configFile)) {
                properties = new Yaml().loadAs(fr, Properties.class);
                uri = new URI(properties.getProperty("URI"));
                token = properties.getProperty("Token");
                ID = properties.getProperty("ThisID");
                newWebsocketClient();

                Bukkit.getScheduler().runTaskTimer(this, () -> new Thread(() -> {
                    if (webSocketClient.isClosed()) {
                        newWebsocketClient();
                    }
                }).start(), 240L, 240L); // 第一个参数是延迟时间（tick），第二个参数是周期时间（tick）
            } catch (URISyntaxException e) {
                getLogger().info("URI 格式有误");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else if (!configFile.exists()) {
            try {
                if (configFile.createNewFile()) {
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
        CountDownLatch latch = new CountDownLatch(1);
        WebSocketClient wsc = new WebSocketClient(uri, header) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                UniverseChannelMessage ucm = new UniverseChannelMessage();
                ucm.tag = "Universe";
                ucm.message = "RegisterListener";
                ucm.args.put("Tag", new String[]{"Minecraft-Connect-Spigot", "Minecraft-Connect", ID});
                send(gson.toJson(ucm));
                getLogger().info("已与宇宙建立连结");
                ucm.tag = "Minecraft-Connect";
                ucm.message = "InfoUpload";
                ucm.args.clear();
                ucm.args.put("BuildVersion", Bukkit.getVersion());
                send(gson.toJson(ucm));
                latch.countDown();
            }

            @Override
            public void onMessage(String s) {
                UniverseChannelMessage ucm = gson.fromJson(s, UniverseChannelMessage.class);
                switch (ucm.message) {
                    case "ForwardChat" -> {
                        ComponentBuilder bc = new ComponentBuilder();
                        ForwardChat fc = gson.fromJson(s, ForwardChat.class);
                        net.md_5.bungee.api.ChatColor color = net.md_5.bungee.api.ChatColor.of(new Color(fc.RGB[0],fc.RGB[1],fc.RGB[2]));
                        TextComponent name = new TextComponent(color + "[" + fc.MCMsg.sender.getName() + "]");
                        name.setHoverEvent(
                                new HoverEvent(
                                        HoverEvent.Action.SHOW_TEXT,
                                        new Text(ChatColor.DARK_GREEN + fc.MCMsg.sender.getPlatform() + " " + fc.MCMsg.sender.getId())
                                )
                        );
                        bc.append(name).append(": ");
                        for (MsgField f : fc.MCMsg.messageFields) {
                            if (f instanceof AtField af) {
                                TextComponent tc = new TextComponent(ChatColor.YELLOW + f.getAsString());
                                tc.setHoverEvent(
                                        new HoverEvent(
                                                HoverEvent.Action.SHOW_TEXT,
                                                new Text(ChatColor.DARK_GREEN + af.target.getPlatform() + " " + af.target.getId())
                                        )
                                );
                                bc.append(tc);
                            } else if (f instanceof ImageField imgF) {
                                try {
                                    BufferedImage bi = ImageIO.read(imgF.url);
                                    int maxSize = 40;
                                    double scale = Math.min(
                                            (double) maxSize / bi.getWidth(),
                                            (double) maxSize / bi.getHeight()
                                    );
                                    int targetWidth  = (int) (bi.getWidth()  * scale);
                                    int targetHeight = (int) (bi.getHeight() * scale);
                                    Image scaled = bi.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
                                    BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
                                    Graphics2D g = resized.createGraphics();
                                    g.drawImage(scaled, 0, 0, null);
                                    g.dispose();
                                    StringBuilder sb = new StringBuilder();

                                    for (int y = 0; y < resized.getHeight(); y++) {
                                        for (int x = 0; x < resized.getWidth(); x++) {
                                            int rgb = resized.getRGB(x, y);

                                            int r = (rgb >> 16) & 0xFF;
                                            int gr = (rgb >> 8) & 0xFF;
                                            int b = rgb & 0xFF;

                                            sb.append(colorHex(r, gr, b)).append("█");
                                        }
                                        sb.append("\n");
                                    }

                                    BaseComponent[] hover = TextComponent.fromLegacyText(sb.toString());
                                    TextComponent tc = new TextComponent(ChatColor.UNDERLINE + "[图片]");
                                    tc.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover)));
                                    bc.append(tc);
                                } catch (Exception e) {
                                    TextComponent tc = new TextComponent(f.getAsString());
                                    tc.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("[图片]")));
                                    bc.append(tc);
                                }
                            } else {
                                TextComponent tc = new TextComponent(f.getAsString());
                                tc.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("")));
                                bc.append(tc);
                            }
                        }
                        Bukkit.spigot().broadcast(bc.create());
                    }
                }
            }

            @Override
            public void onClose(int i, String s, boolean b) {
                if (i != -1) getLogger().warning("与宇宙的连接断开" + i);
            }

            @Override
            public void onError(Exception e) {

            }
        };
        wsc.connect();
        try {
            //noinspection ResultOfMethodCallIgnored
            latch.await(10, TimeUnit.SECONDS);
            if (wsc.isOpen()) webSocketClient = wsc;
            else wsc.close();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private String colorHex(int r, int g, int b) {
        return String.format("§x§%x§%x§%x§%x§%x§%x",
                (r >> 4), (r & 0xF),
                (g >> 4), (g & 0xF),
                (b >> 4), (b & 0xF));
    }
}
