package com.nekoyu.Universe.ConfigureProcessor;

public class CFGFileSyntaxException extends Exception{

    private final Object detailMessage;

    public CFGFileSyntaxException(String message) {
        this.detailMessage = message;
    }
}
