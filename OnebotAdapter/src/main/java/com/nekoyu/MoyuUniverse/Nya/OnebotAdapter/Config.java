package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import java.util.ArrayList;
import java.util.List;

public class Config {
    String ID;
    String Token;
    String URI;
    boolean EnableQZone = false;
    RemoteWebDriver RemoteWebDriver;
    List<Long> BlockedUsers = new ArrayList<>();
    List<Long> DegradedUsers = new ArrayList<>();

    public static class RemoteWebDriver {
        String url;
    }
}
