package com.nekoyu.Universe.LawsLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Law {
    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    public String ID;
    public String[] Dependencies;
    public boolean isRunning;

    public Law() {}

    public abstract void prepare();

    public abstract void run();

    public abstract void stop();
}
