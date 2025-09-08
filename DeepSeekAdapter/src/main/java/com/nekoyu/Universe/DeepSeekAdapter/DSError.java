package com.nekoyu.Universe.DeepSeekAdapter;

public class DSError {
    Error error;
    String id;
    String request_id;

    public static class Error {
        String code;
        String param;
        String message;
        String type;
    }
}
