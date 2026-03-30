package com.nekoyu.Universe.API.MessageChannel.MessageField;

import java.net.URL;

public class VideoField extends FileField {
    public VideoField(URL url) {
        super(url);
        super.type = "video";
    }

    @Override
    public String toString() {
        return "[视频]";
    }
}
