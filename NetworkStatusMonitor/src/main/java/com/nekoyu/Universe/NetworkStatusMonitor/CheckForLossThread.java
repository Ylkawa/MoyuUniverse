package com.nekoyu.Universe.NetworkStatusMonitor;

import com.nekoyu.Universe.API.PlaceHolder;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;

public class CheckForLossThread implements Runnable{
    String name;
    InetAddress inetAddresses;
    Logger logger;
    @Override
    public void run() {
        try {
            int fails = 0;
            for (int i = 0; i < 10; i++) {
                if (!inetAddresses.isReachable(1000)) fails++;
            }
            PlaceHolder.setReplacement("Loss:"+name, fails/1000+"%");
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

    public CheckForLossThread(String name, InetAddress inetAddresses, Logger logger) {
        this.name = name;
        this.inetAddresses = inetAddresses;
        this.logger = logger;
    }
}
