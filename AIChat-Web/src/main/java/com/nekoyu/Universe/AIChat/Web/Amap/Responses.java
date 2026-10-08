package com.nekoyu.Universe.AIChat.Web.Amap;

import java.util.List;

/** Typed Web Service v3 responses; optional scalar fields may be null. */
public final class Responses {
    private Responses() {}

    public static class Base {
        public String status, info, infocode, count;
    }

    public static class DistrictResponse extends Base {
        public List<District> districts;
    }
    public static class District {
        public String name, adcode, citycode, level, center;
        public List<District> districts;
    }

    public static class WeatherResponse extends Base {
        public List<Live> lives;
        public List<Forecast> forecasts;
    }
    public static class Live {
        public String province, city, adcode, weather, temperature;
        public String winddirection, windpower, humidity, reporttime;
    }
    public static class Forecast {
        public String province, city, adcode, reporttime;
        public List<Cast> casts;
    }
    public static class Cast {
        public String date, week, dayweather, nightweather, daytemp, nighttemp;
        public String daywind, nightwind, daypower, nightpower;
    }

    public static class GeocodeResponse extends Base {
        public List<Geocode> geocodes;
    }
    public static class Geocode {
        public String formatted_address, province, city, district, adcode, location, level;
    }
    public static class RegeocodeResponse extends Base {
        public Regeocode regeocode;
    }
    public static class Regeocode {
        public String formatted_address;
        public AddressComponent addressComponent;
    }
    public static class AddressComponent {
        public String province, city, district, township, adcode;
    }

    public static class PoiResponse extends Base {
        public List<Poi> pois;
    }
    public static class Poi {
        public String id, name, type, address, location, tel, distance;
        public String pname, cityname, adname, adcode;
    }

    public static class RouteResponse extends Base {
        public Route route;
    }
    public static class Route {
        public String origin, destination;
        public List<Path> paths;
    }
    public static class Path {
        public String distance, duration, strategy, tolls, restriction;
        public List<Step> steps;
    }
    public static class Step {
        public String instruction, road, distance, duration;
    }
}
