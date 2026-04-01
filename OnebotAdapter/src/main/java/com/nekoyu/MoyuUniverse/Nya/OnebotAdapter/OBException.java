package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

public class OBException extends RuntimeException {
    int retcode;

    public OBException(int retcode, String message) {
        super(message);
        this.retcode = retcode;
    }
}
