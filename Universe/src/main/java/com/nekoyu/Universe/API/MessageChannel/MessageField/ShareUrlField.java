package com.nekoyu.Universe.API.MessageChannel.MessageField;

import java.net.URL;

public class ShareUrlField extends MsgField {
    public String title;
    public URL uri;
    public ImageField image;

    public ShareUrlField(String title, URL uri) {
        super.type = "shareUri";
        this.title = title;
        this.uri = uri;
    }

    @Override
    public String getAsString() {
        return "[分享链接, "+title+": "+uri+"]";
    }
}
