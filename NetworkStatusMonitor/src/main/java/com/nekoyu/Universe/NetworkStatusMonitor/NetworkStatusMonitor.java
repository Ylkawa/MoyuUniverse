package com.nekoyu.Universe.NetworkStatusMonitor;

import com.google.gson.Gson;
import com.nekoyu.Universe.LawsLoader.Law;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class NetworkStatusMonitor extends Law {
    Config cfg;
    Map<String, InetAddress> watchTarget;

    @Override
    public boolean prepare() {
        getConfigDir();
        try {
            cfg = new Gson().fromJson(new FileReader("./config/NetworkStatusMonitor/config.json"), Config.class);
            watchTarget = new HashMap<>();
            for (Map.Entry<String, String> entry : cfg.watch.entrySet()) {
                try {
                    watchTarget.put(entry.getKey(), InetAddress.getByName(entry.getValue()));
                } catch (UnknownHostException e) {
                    logger.error("未知的主机名", e);
                }
            }
        } catch (FileNotFoundException e) {
            try {
                File conf = new File("./config/NetworkStatusMonitor/config.json");
                conf.createNewFile();
                try (FileWriter fw = new FileWriter(conf)) {
                    cfg = new Config();
                    cfg.watch.put("watch_target", "localhost");
                    fw.write(new Gson().toJson(cfg));
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            return false;
        }
        return true;
    }

    @Override
    public void run() {
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                for (Map.Entry<String, InetAddress> entry : watchTarget.entrySet()) {
                    new Thread(new CheckForLossThread(entry.getKey(), entry.getValue(), logger)).start();
                }
            }
        }, 1000, 1000);
    }

    @Override
    public void stop() {

    }
}
