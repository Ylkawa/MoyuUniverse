package com.nekoyu.Universe.DeepSeekAdapter;

public class OutOfRequestLimit extends RuntimeException {
    public OutOfRequestLimit(String message) {
        super(message);
    }
}
