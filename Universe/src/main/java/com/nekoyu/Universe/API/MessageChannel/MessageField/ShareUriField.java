package com.nekoyu.Universe.API.MessageChannel.MessageField;

import java.net.URI;

public class ShareUriField extends MsgField {
    String title;
    URI uri;

    public ShareUriField(String title, URI uri) {
        super.type = "shareUri";
        this.title = title;
        this.uri = uri;
    }

    @Override
    public String getAsString() {
        return "[分享链接, "+title+": "+uri+"]";
    }
}
