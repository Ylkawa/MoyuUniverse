package com.nekoyu.AmapAPI.v3;

public class AmapException extends RuntimeException {
    String rawResponse;

    public AmapException(String message) {
        super(message);
    }
}
