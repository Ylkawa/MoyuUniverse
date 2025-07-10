package com.nekoyu.Universe;

import com.nekoyu.Universe.API.UniverseListener;
import com.nekoyu.Universe.LawsLoader.Law;

import java.util.Map;

public class MinecraftConnectUniverse extends Law implements UniverseListener {
    @Override
    public void prepare() {

    }

    @Override
    public void run() {
        Universe.UniverseChannel.registerListener(this);
    }

    @Override
    public void stop() {
        Universe.UniverseChannel.unRegisterListener(this);
    }

    @Override
    public void onMessage(Map message) {

    }
}
