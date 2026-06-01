package com.nekoyu.Universe.MinecraftConnect;

import com.nekoyu.Universe.API.UniverseChannelMessage;

import java.net.URL;
import java.util.LinkedList;

public class ForwardChat extends UniverseChannelMessage {
    public LinkedList<Segment> segments = new LinkedList<>();
    public Account sender = new Account();

    public static class Segment {
        public String type;
    }

    public static class Text extends Segment {
        public String text;

        public Text(String text) {
            this.text = text;
        }
    }

    public static class Image extends Segment {
        public URL url;
        public String description;
    }

    public static class At extends Segment {
        Account target;
    }

    public static class Account {
        String locationId;
        String name;
        public int[] RGB;
    }
}
