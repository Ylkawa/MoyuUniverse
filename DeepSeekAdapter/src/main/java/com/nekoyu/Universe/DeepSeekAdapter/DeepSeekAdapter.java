package com.nekoyu.Universe.DeepSeekAdapter;

import com.google.gson.Gson;
import com.nekoyu.Universe.API.MessageChannel.Solver;
import com.nekoyu.Universe.ConfigureProcessor.CFGFileSyntaxException;
import com.nekoyu.Universe.ConfigureProcessor.ConfigureProcessor;
import com.nekoyu.Universe.DeepSeekAdapter.ContentPiece.ImageUrlPiece;
import com.nekoyu.Universe.DeepSeekAdapter.ContentPiece.TextPiece;
import com.nekoyu.Universe.DeepSeekAdapter.SpecialDesignedReqRespBodies.qwen3_asr_flash_req;
import com.nekoyu.Universe.DeepSeekAdapter.SpecialDesignedReqRespBodies.qwen3_asr_flash_resp;
import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Objects;

public class DeepSeekAdapter extends Law {
    OkHttpClient client = new OkHttpClient();
    Gson gson = new Gson();

    @Override
    public boolean prepare() {
        File configDic = new File("./config/DeepSeekAdapter/");
        if (!configDic.exists()) configDic.mkdir();
        for (File file : Objects.requireNonNull(configDic.listFiles())) {
            if (file.getName().endsWith(".yml")) {
                ConfigureProcessor cp = new ConfigureProcessor(file, true);
                cp.requireNode("id", "\\w+");
                cp.requireNode("base_url", "(https?)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]+[-A-Za-z0-9+&@#/%=~_|]", "https://api.deepseek.com");
                cp.requireNode("api_key", "[-\\w]+");
                try {
                    cp.read();
                } catch (CFGFileSyntaxException e) {
                    return false;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                int checkFor = cp.checkFor();
                if (checkFor == 0) {
                    DeepSeekChannel dsc = new DeepSeekChannel(cp.getNode("id").toString(), cp.getNode("base_url").toString(), cp.getNode("api_key").toString());
                    Universe.Providers.put(dsc.id, dsc);
                    if (cp.getNode("PictureSolver") != null) {
                        Universe.pictureSolver = new Solver() {
                            @Override
                            public String getDescription(URL url) throws DSException {
                                logger.info("尝试解析图片 {}", url.toString());
                                var assistant = dsc.getAssistant(cp.getNode("PictureSolver").toString());
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
                            }
                        };
                    }
                    if (cp.getNode("VoiceSolver") != null) {
                        Universe.voiceSolver = new Solver() {
                            @Override
                            public String getDescription(URL url) throws DSException {
                                logger.info("尝试解析语音 {}", url.toString());
                                var reqBody = new qwen3_asr_flash_req();
                                reqBody.model = cp.getNode("VoiceSolver").toString();
                                var text = new qwen3_asr_flash_req.Input.Message();
                                var ctt1 = new qwen3_asr_flash_req.Input.Message.Ctt();
                                ctt1.text = "";
                                text.content.add(ctt1);
                                text.role = "system";
                                var audio = new qwen3_asr_flash_req.Input.Message();
                                var ctt2 = new qwen3_asr_flash_req.Input.Message.Ctt();
                                ctt2.audio = url.toString();
                                audio.content.add(ctt2);
                                audio.role = "user";
                                reqBody.input.messages.add(text);
                                reqBody.input.messages.add(audio);
                                reqBody.parameters.asr_options.enable_itn = true;

                                Request req = new Request.Builder()
                                        .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation")
                                        .header("Authorization", "Bearer " + cp.getNode("api_key").toString())
                                        .post(RequestBody.create(MediaType.parse("application/json"), gson.toJson(reqBody)))
                                        .build();
                                try (Response response = client.newCall(req).execute()) {
                                    String string = response.body().string();
                                    System.out.println(string);
                                    return gson.fromJson(string, qwen3_asr_flash_resp.class).output.choices[0].message.content[0].text;
                                } catch (Exception e) {
                                    throw new DSException(e.getMessage());
                                }
                            }
                        };
                    }
                    logger.info("已载入 DeepSeek 适配器 {}", dsc.id);
                } else {
                    logger.warn("配置文件 {} 仍存在 {} 个错误，将不会被加载", file.getName(), checkFor);
                }
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