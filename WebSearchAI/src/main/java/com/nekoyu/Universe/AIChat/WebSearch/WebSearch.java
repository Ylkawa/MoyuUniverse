package com.nekoyu.Universe.AIChat.WebSearch;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.nekoyu.Universe.AIChat.AIChat;
import com.nekoyu.Universe.AIChat.AIChatPlugin;
import com.nekoyu.Universe.AIChat.WebSearch.BiliBiliAPI.*;
import com.nekoyu.Universe.AIChat.WebSearch.GoogleWebSearchAPI.SearchResponse;
import com.nekoyu.Universe.DeepSeekAdapter.DeepSeekTool;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class WebSearch extends AIChatPlugin {
    OkHttpClient client;
    Config config;
    Gson gson = new Gson();
    boolean able = true;

    public WebSearch(AIChat aiChat) {
        super(aiChat);
    }

    @Override
    public void onEnable() {
        getConfigDir();
        Proxy proxy = Proxy.NO_PROXY;
        try {
            config = new Gson().fromJson(new FileReader("./config/AIChat/Plugins/WebSearch/config.json"), Config.class);
            if (config.EnableProxy) {
                if (config.HttpProxyURI == null || config.HttpProxyURI.isEmpty()) {
                    logger.error("Proxy URI is null or empty");
                    able = false;
                }
                String[] hostAndPort = config.HttpProxyURI.split("http://")[1].split(":");
                try {
                    InetSocketAddress isa = new InetSocketAddress(hostAndPort[0], Integer.parseInt(hostAndPort[1]));
                    proxy = new Proxy(Proxy.Type.HTTP, isa);
                } catch (Exception e) {
                    logger.error("Proxy URI is invalid");
                    able = false;
                }
            }
        } catch (FileNotFoundException e) {
            config = new Config();
            try (FileWriter fw = new FileWriter("./config/AIChat/Plugins/WebSearch/config.json")) {
                fw.write(new Gson().toJson(config));
            } catch (IOException ex) {
                able = false;
                logger.error(ex.getMessage());
                return;
            }
        }
        client = new OkHttpClient.Builder()
                .proxy(proxy)
                .build();

        DeepSeekTool dst = new DeepSeekTool("Google搜索",
                "使用Google的API在全网搜索内容，仅当用户要求或者要回答的内容具有时效性时使用",
                args -> search(args.get("搜索词")),
                new HashMap<>() {{
                    put("搜索词", new DeepSeekTool.Function.Parameters.Property("搜索词"));
                }},
                new String[]{"搜索词"});
        registerTool("WebSearch", dst);

        DeepSeekTool visitUrl = new DeepSeekTool("访问网页",
                """
                        获取部分受支持的网页中的信息（内容会被精简）仅支持哔哩哔哩视频和用户空间
                        例: https://www.bilibili.com/video/BV1SC4y1J7De , https://space.bilibili.com/497423225""",
                args -> visitUrl(args.get("网址")),
                new HashMap<>() {{
                    put("网址", new DeepSeekTool.Function.Parameters.Property("目标访问网址"));
                }},
                new String[]{"网址"});
        registerTool("VisitURL", visitUrl);
    }

    private String search(String content) {
        // 构建请求
        HttpUrl url = HttpUrl.parse("https://www.googleapis.com/customsearch/v1")
                .newBuilder()
                .addQueryParameter("key", config.SearchAPIKey)
                .addQueryParameter("cx", config.SearchEngineID)
                .addQueryParameter("q", content)
                .build();
        Request req = new Request.Builder()
                .url(url)
                .get()
                .build();
        try (Response response = client.newCall(req).execute()) {
            if (response.isSuccessful()) {
                try {
                    SearchResponse searchR = gson.fromJson(response.body().string(), SearchResponse.class);
                    // 构建自然语言响应
                    StringBuilder sb = new StringBuilder();
                    if (searchR.items != null && !searchR.items.isEmpty()) {
                        sb.append("\"").append(searchR.queries.request.get(0).searchTerms).append("\" 的搜索结果");
                        for (SearchResponse.Item item : searchR.items) {
                            sb.append("{\n");
                            sb.append("「").append(item.title).append("」 - ").append(item.link).append("\n");
                            sb.append("摘要: ").append(item.snippet).append("\n");
                            sb.append("}\n\n");
                        }
                        logger.debug(sb.toString());
                        return sb.toString();
                    } else return "未搜索到结果";
                } catch (JsonSyntaxException e) {
                    logger.error("Google API返回意料之外的结果", e);
                    return "搜索发生错误: " + e.getMessage();
                }
            } else {
                return "搜索失败，状态码: " + response.code();
            }
        } catch (IOException e) {
            return "搜索异常: " + e.getMessage();
        }
    }

    private String visitUrl(String url) {
        Matcher domain = Pattern.compile("^https?://([^/]+)(?:/.*)?$").matcher(url);
        if (domain.find()) {
            switch (domain.group(1)) {
                case "www.bilibili.com" -> {
                    Pattern pattern = Pattern.compile("https?://www\\.bilibili\\.com/video/(BV\\w+)(?:\\?.*)?");
                    Matcher matcher = pattern.matcher(url);

                    if (matcher.find()) {
                        String bv = matcher.group(1);  // 获取第一个捕获组
                        try {
                            VideoInfo info = Client.getVideoInfo(bv);
                            CommentList cl = Client.getVideoCommentList(bv);
                            StringBuilder sb = new StringBuilder();
                            switch (info.code) {
                                case 0:
                                    sb.append("「").append(bv).append("」").append("的信息:\n");
                                    sb.append("作者: ").append(info.data.owner.name).append(" (UID:").append(info.data.owner.mid).append(")");
                                    sb.append("标题: ").append(info.data.title).append("\n");
                                    sb.append("简介: ").append(info.data.desc).append("\n");
                                    sb.append("数据(播放量/点赞/投币/收藏/转发/弹幕/评论): ")
                                            .append(info.data.stat.view).append("/")
                                            .append(info.data.stat.like).append("/")
                                            .append(info.data.stat.coin).append("/")
                                            .append(info.data.stat.favorite).append("/")
                                            .append(info.data.stat.share).append("/")
                                            .append(info.data.stat.danmaku).append("/")
                                            .append(info.data.stat.reply).append("/")
                                            .append("\n");
                                    if (info.data.stat.his_rank != 0) {
                                        sb.append("历史最高排名: ").append(info.data.stat.his_rank).append("\n");
                                    }
                                    if (info.data.stat.now_rank != 0) {
                                        sb.append("当前排名: ").append(info.data.stat.now_rank).append("\n");
                                    }
                                    if (!info.data.argue_info.argue_msg.isBlank()) {
                                        sb.append("争议信息: ").append(info.data.argue_info.argue_msg).append("\n");
                                    }
                                    if (info.data.honor_reply.honor != null) {
                                        sb.append("稿件荣誉: ");
                                        for (var honor : info.data.honor_reply.honor) {
                                            sb.append(honor.desc).append(" ");
                                        }
                                        sb.append("\n");
                                    }
                                    sb.append("\n\n评论区");
                                    short i = 0;
                                    for (var reply : cl.data.replies) {
                                        if (i >= 5) break;
                                        sb.append("\n").append(reply.member.uname).append("(").append(reply.member.sex).append(", Lv.").append(reply.member.level_info.current_level).append(", ").append(reply.reply_control.location).append(", ").append(reply.reply_control.time_desc).append("): ").append(reply.content.message);
                                        i++;
                                    }
                                    return sb.toString();
                                case -400:
                                    return bv + "请求错误";
                                case -403:
                                    return bv + "权限不足";
                                case -404:
                                    return bv + "不存在";
                                case 62002:
                                    return bv + "不可见";
                                case 62004:
                                    return bv + "审核中";
                                case 62012:
                                    return bv + "仅UP主自己可见";
                                default:
                                    return bv + "未知响应码" + info.code;
                            }
                        } catch (IOException e) {
                            return "请求失败" + e.getMessage();
                        }
                    } else {
                        return "不支持的链接类型";
                    }
                }
                case "space.bilibili.com" -> {
                    Matcher matcher = Pattern.compile("^https?://space\\.bilibili\\.com/(\\d+)(?:[/?#].*)?$").matcher(url);
                    if (matcher.find()) {
                        String uid = matcher.group(1);
                        try {
                            UserInfo ui = Client.getUserInfo(uid);
                            DynamicList dl = Client.getUserDynamicList(uid);
                            StringBuilder respBuilder = new StringBuilder();
                            respBuilder.append(ui.data.name).append("(").append(ui.data.mid).append(")").append("的信息:");
                            respBuilder.append("\nLv.").append(ui.data.level).append(" (0~6)");
                            if (ui.data.vip.type == 2) respBuilder.append(ui.data.vip.label.text);
                            respBuilder.append("\n个性签名: ").append(ui.data.sign);
                            respBuilder.append("\n生日: ").append(ui.data.birthday);
                            respBuilder.append("\n性别: ").append(ui.data.sex);
                            respBuilder.append("\n账号等级(Lv.0~6): ").append(ui.data.level);
                            if (ui.data.fans_medal != null && ui.data.fans_medal.show && !ui.data.fans_medal.wear) {
                                respBuilder.append("\n佩戴的粉丝勋章: ").append(ui.data.fans_medal.medal.medal_name).append("(Lv.").append(ui.data.fans_medal.medal.level).append(")");
                            }
                            if (ui.data.live_room != null) {
                                respBuilder.append("\n");
                                if (ui.data.live_room.liveStatus == 0) {
                                    respBuilder.append("(未在直播)");
                                } else if (ui.data.live_room.liveStatus == 1) {
                                    respBuilder.append("(直播中)");
                                }
                                respBuilder.append("直播间(ID:").append(ui.data.live_room.roomid).append("): ").append(ui.data.live_room.title).append(" ").append(ui.data.live_room.watched_show.text_large);
                            }
                            if (dl.data != null) { // 添加动态信息
                                respBuilder.append("\n动态: ");
                                int times = 0;
                                for (var item : dl.data.items) {
                                    if (times == 10) break;
                                    times++;
                                    switch (item.type) {
                                        case "DYNAMIC_TYPE_AV" -> {
                                            respBuilder.append("\n{");
                                            for (var module : item.modules) {
                                                switch (module.module_type) {
                                                    case "MODULE_TYPE_AUTHOR" -> {
                                                        var author = module.module_author;
                                                        respBuilder.append("\n").append(author.pub_text);
                                                    }
                                                    case "MODULE_TYPE_DYNAMIC" -> {
                                                        var dynamic = module.module_dynamic;
                                                        respBuilder.append("\n标题: ").append(dynamic.dyn_archive.title);
                                                        respBuilder.append("\n简介: ").append(dynamic.dyn_archive.desc);
                                                        respBuilder.append("\n时长: ").append(dynamic.dyn_archive.duration_text);
                                                        respBuilder.append("\n点赞/弹幕/播放量(视频): ").append(dynamic.dyn_archive.stat.like).append("/").append(dynamic.dyn_archive.stat.danmaku).append("/").append(dynamic.dyn_archive.stat.play);
                                                        respBuilder.append("\nBVID: ").append(dynamic.dyn_archive.bvid);
                                                    }
                                                    case "MODULE_TYPE_STAT" -> {
                                                        var stat = module.module_stat;
                                                        respBuilder.append("\n\n点赞/评论/转发(动态): ").append(stat.like.count).append("/").append(stat.comment.count).append("/").append(stat.forward.count);
                                                    }
                                                }
                                            }
                                            respBuilder.append("\n}");
                                        }
                                        default -> times--;
                                    }
                                }
                            }
                            logger.debug(respBuilder.toString());
                            return respBuilder.toString();
                        } catch (IOException e) {
                            return "未知错误: " + e.getMessage();
                        } catch (NullPointerException e) {
                            return "获取信息失败!";
                        }
                    }
                }
                default -> {
                    return "不支持的链接类型";
                }
            }
        }
        // 匹配B站视频的情况
        return "未知原因导致访问失败";
    }
}
