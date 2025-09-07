package com.nekoyu.AmapAPI.v3.geocode;

import com.nekoyu.AmapAPI.v3.Response;

import java.util.LinkedList;

public class RegeoResponse extends Response {
    public RegeoResponse() {
        this.regeocode = new Regeocode();
    }

    public Regeocode regeocode;

    public static class Regeocode {
        public String formatted_address; // e.g. 北京市朝阳区望京街道方恒国际中心B座方恒国际
        public AddressComponent addressComponent;
        public LinkedList<Poi> pois;
        public LinkedList<Road> roads;
        public LinkedList<Roadinter> roadinters;
        public LinkedList<Aoi> aois;

        public static class AddressComponent {
            public String country;
            public String province;
            public String city;
            public int citycode;
            public String district;
            public int adcode;
            public String township;
            public long towncode;
            public Building neighborhood;
            public Building building;
            public StreetNumber streetNumber;
            public LinkedList<BusinessAreas> businessAreas;


            public static class Building {
                public String name;
                public String type;
            }

            public static class StreetNumber {
                public String street;
                public String number;
                public String location;
                public String direction;
                public float distance;
            }

            public static class BusinessAreas {
                public String location;
                public String name;
                public int id;
            }
        }

        public static class Poi{
            public String id;
            public String name;
            public String type;
            public String tel;
            public String direction;
            public float distance;
            public String location;
            public String address;
            public String poiweight;
            public String businessarea;
        }

        public static class Road {
            public String id;
            public String name;
            public String direction;
            public float distance;
            public String location;
        }

        public static class Roadinter {
            public String direction;
            public float distance;
            public String location;
            public String first_id;
            public String first_name;
            public String second_id;
            public String second_name;
        }

        public static class Aoi {
            public String id;
            public String name;
            public int adcode;
            public String location;
            public String area;
            public String distance;
            public String type;
        }
    }
}
