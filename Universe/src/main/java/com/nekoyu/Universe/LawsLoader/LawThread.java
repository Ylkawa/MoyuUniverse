package com.nekoyu.Universe.LawsLoader;

public class LawThread implements Runnable {
    Law law;
    @Override
    public void run() {
        law.run();
    }

    public LawThread(Law law) {
        this.law = law;
    }
}
