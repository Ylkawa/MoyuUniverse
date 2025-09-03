package com.nekoyu.Universe.API.MessageChannel.MessageField;

import java.net.URI;

public class FileField extends MsgField {
    public URI file;
    private String content;

    public FileField() {
        super.type = "file";
    }

    @Override
    public String getAsString() {
        return "[文件]";
    }
}
