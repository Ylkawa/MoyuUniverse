package com.nekoyu.Universe.API.MessageChannel.MessageField;

public class LocationField extends MsgField {
    double lat;
    double lon;

    public LocationField(double lat, double lon) {
        super.type = "location";
        this.lat = lat;
        this.lon = lon;
    }

    @Override
    public String toString() {
        return "[位置分享: 经度: "+lon+", 纬度: "+lat+"]";
    }
}
