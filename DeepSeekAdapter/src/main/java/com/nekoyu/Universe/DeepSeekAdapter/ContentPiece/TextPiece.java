package com.nekoyu.Universe.DeepSeekAdapter.ContentPiece;

public class TextPiece extends ContentPiece {
    String text;

    public TextPiece(String text) {
        super.type = "text";
        this.text = text;
    }
}
