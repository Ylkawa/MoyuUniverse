package com.nekoyu.Universe.LawsLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public abstract class Law {
    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    public String ID;
    public String[] Dependencies;
    public boolean ableToRun;
    public boolean isRunning;
    public boolean isPrepared;

    public Law() {}

    public abstract boolean prepare();
    public abstract void run();
    public abstract void stop();

    protected File getConfigDir() {
        File file = new File("./config/"+this.ID);
        if (!file.exists()) file.mkdirs();
        return file;
    }

    protected File getDataDir() {
        File file = new File("./data/"+this.ID);
        if (!file.exists()) file.mkdirs();
        return file;
    }
}
