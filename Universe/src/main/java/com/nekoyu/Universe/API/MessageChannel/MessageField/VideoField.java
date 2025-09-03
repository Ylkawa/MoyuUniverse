package com.nekoyu.Universe.API.MessageChannel.MessageField;

public class VideoField extends FileField {
    public VideoField() {
        super.type = "video";
    }

    public String getAsString() {
        return "[视频]";
    }
}
