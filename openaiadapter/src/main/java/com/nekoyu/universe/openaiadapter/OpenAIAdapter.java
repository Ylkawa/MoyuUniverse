package com.nekoyu.universe.openaiadapter;

import com.google.gson.Gson;
import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Objects;

public class OpenAIAdapter extends Law {
    Gson gson = new Gson();

    @Override
    public boolean prepare() {
        for (File file : Objects.requireNonNull(getConfigDir().listFiles())) {
            try (FileReader fr = new FileReader(file)) {
                Config cfg = gson.fromJson(fr, Config.class);
                OpenAIChannel channel = new OpenAIChannel();
                channel.apikey = cfg.APIKey;
                channel.setBaseurl(cfg.BaseUrl);
                channel.defaultModel = cfg.DefaultModel;
                channel.logger = LoggerFactory.getLogger("OpenAI C - " + cfg.ProviderId);
                Universe.Providers.put(cfg.ProviderId, channel);
            } catch (FileNotFoundException e) {
                logger.error("能触发这个报错这辈子有了👍", e);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
        return true;
    }

    @Override
    public void run() {

    }

    @Override
    public void stop() {

    }
}
