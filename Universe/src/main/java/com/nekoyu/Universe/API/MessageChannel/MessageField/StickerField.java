package com.nekoyu.Universe.API.MessageChannel.MessageField;

import java.net.URL;

public class StickerField extends ImageField {
    public StickerField(URL url) {
        super(url);
    }

    @Override
    public String toString() {
        return "[动画表情]";
    }
}
