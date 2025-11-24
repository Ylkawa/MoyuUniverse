package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece;

public class TextPiece extends ContentPiece {
    String text;

    public TextPiece(String text) {
        super.type = "text";
        this.text = text;
    }
}
