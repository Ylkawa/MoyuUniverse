package com.nekoyu.Universe.API.MessageChannel.MessageField;

public class MapField extends MsgField {
    double lat;
    double lon;

    public MapField(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
    }
}
