package com.nekoyu.Universe.ColorGroupName;

import com.google.gson.Gson;
import com.nekoyu.Universe.API.MessageChannel.UnsupportedAction;
import com.nekoyu.Universe.API.PlaceHolder;
import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class ColorGroupName extends Law {
    public static final Gson gson = new Gson();
    Config cfg = null;

    @Override
    public boolean prepare() {
        File configDic = new File("./config/ColorGroupName/");
        if (!configDic.exists()) configDic.mkdir();
        File configFile = new File("./config/ColorGroupName/config.yml");

        try {
            cfg = gson.fromJson(new FileReader(configFile), Config.class);
        } catch (FileNotFoundException e) {
            cfg = new Config();
            cfg.Settings.put("example-Session", new Config.Set("example-Template", 10000));
            try {
                configFile.createNewFile();
                saveConf(configFile);
            } catch (IOException ex) {
                logger.error(e.getMessage(), e);
                return false;
            }
            return true;
        }

        return true;
    }

    private void saveConf(File configFile) throws IOException {
        try (FileWriter fw = new FileWriter(configFile)) {
            fw.write(gson.toJson(cfg));
            fw.flush();
        }
    }

    @Override
    public void run() {
        Map<String, String> currentName = new HashMap<>();
        if (cfg.Settings != null) for (Map.Entry<String, Config.Set> entry : cfg.Settings.entrySet()) {
            new Thread(() -> {
                boolean keepAble = true;
                while (keepAble) {
                    try {
                        Thread.sleep(entry.getValue().delay);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    String sessionName = PlaceHolder.replace(entry.getValue().template);
                    if (!sessionName.equals(currentName.get(entry.getKey()))) {
                        currentName.put(entry.getKey(), sessionName);
                        try {
                            Universe.MessageChannelManager.setSessionName(entry.getKey(), sessionName);
                        } catch (UnsupportedAction e) {
                            logger.warn("{}: {}", e.getMessage(), entry.getKey());
                            keepAble = false;
                        }
                    }
                }
            }).start();
        }
    }

    @Override
    public void stop() {

    }
}
