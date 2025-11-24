package com.nekoyu.universe.openaiadapter;

import com.google.gson.Gson;
import com.nekoyu.Universe.LawsLoader.Law;

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

            } catch (FileNotFoundException ignored) {
                logger.error("能触发这个报错这辈子有了");
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
