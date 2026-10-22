package com.nekoyu.Universe.LawsLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public abstract class Law {
    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    public String ID;
    /** @deprecated Declare dependencies in law.yml. */
    @Deprecated
    public String[] Dependencies;
    public volatile boolean ableToRun;
    public volatile boolean isRunning;
    public volatile boolean isPrepared;

    public Law() {}

    public abstract boolean prepare();
    public abstract void run();
    public abstract void stop();

    /**
     * Null means run() returning successfully marks readiness. Override with a stable
     * future for asynchronous initialization or a long-running run(); complete it
     * only when services are usable. Invoked before run(), on the law's thread.
     */
    public java.util.concurrent.CompletionStage<Void> readiness() { return null; }

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
