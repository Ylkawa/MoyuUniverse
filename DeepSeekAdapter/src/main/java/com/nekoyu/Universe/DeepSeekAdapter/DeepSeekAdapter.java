package com.nekoyu.Universe.DeepSeekAdapter;

import com.google.gson.Gson;
import com.nekoyu.Universe.DeepSeekAdapter.ContentPiece.ImageUrlPiece;
import com.nekoyu.Universe.DeepSeekAdapter.ContentPiece.TextPiece;
import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Objects;

public class DeepSeekAdapter extends Law {
    Gson gson = new Gson();

    @Override
    public boolean prepare() {
        File configDic = new File("./config/DeepSeekAdapter/");
        if (!configDic.exists()) configDic.mkdir();
        for (File file : Objects.requireNonNull(configDic.listFiles())) {
            if (file.getName().endsWith(".json")) try (FileReader fr = new FileReader(file)) {
                Config config = gson.fromJson(fr, Config.class);
                DeepSeekChannel dsc = new DeepSeekChannel(config.ProviderId, config.Base_Url, config.API_Key);
                if (config.Picture_Solver != null) {
                    Universe.pictureSolver = url -> {
                        logger.info("尝试解析图片 {}", url.toString());
                        var assistant = dsc.getAssistant(config.Picture_Solver);
                        var ml = new MessageList();
                        var msg = new ArrayMessage();
                        msg.role = "user";
                        msg.content.add(new ImageUrlPiece(url.toString()));
                        msg.content.add(new TextPiece("请概括此图片的内容"));
                        ml.addMessage(msg);
                        try {
                            return assistant.request(ml).choices[0].message.content;
                        } catch (DSException e) {
                            logger.error(e.getMessage(), e);
                            logger.error(e.rawResponse);
                            throw e;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    };
                }
                Universe.Providers.put(config.ProviderId, dsc);
                logger.info("已载入 DeepSeek 适配器 {}", dsc.id);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return true;
//        cp.requireNode("base_url", Pattern.compile("(https?)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]+[-A-Za-z0-9+&@#/%=~_|]"), "https://api.deepseek.com");
//        cp.requireNode("api_key", Pattern.compile("\\w+"));
//        int checkFor = cp.checkFor();
//        if (checkFor == 0) {
//            return true;
//        } else {
//            return false;
//        }
    }

    @Override
    public void run() {

    }

    @Override
    public void stop() {

    }
}