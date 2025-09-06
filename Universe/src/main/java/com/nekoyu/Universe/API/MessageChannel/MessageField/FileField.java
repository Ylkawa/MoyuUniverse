package com.nekoyu.Universe.API.MessageChannel.MessageField;

import java.net.URL;

public class FileField extends MsgField {
    public URL url;

    public FileField(URL url) {
        this.url = url;
        super.type = "file";
    }

    @Override
    public String getAsString() {
        return "[文件]";
    }
}
