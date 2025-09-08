package com.nekoyu.Universe.DeepSeekAdapter;

public class DSException extends RuntimeException {
    String rawResponse;
    DSError error;

    public DSException(String message) {
        super(message);
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }
}
