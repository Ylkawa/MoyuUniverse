package com.nekoyu.Universe.API.MessageChannel.MessageField;

import java.net.URI;

public class ShareUriField extends MsgField {
    String title;
    URI share;

    public ShareUriField(String title, URI share) {
        this.title = title;
        this.share = share;
    }
}
