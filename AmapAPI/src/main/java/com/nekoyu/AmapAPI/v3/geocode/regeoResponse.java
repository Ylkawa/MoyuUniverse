package com.nekoyu.AmapAPI.v3.geocode;

import com.nekoyu.AmapAPI.v3.Response;

import java.util.LinkedList;
import java.util.List;

public class regeoResponse extends Response {
    public regeoResponse() {
        this.regeocode = new Regeocode();
    }

    Regeocode regeocode;

    public static class Regeocode {
        String formatted_address; // e.g. 北京市朝阳区望京街道方恒国际中心B座方恒国际
        AddressComponent addressComponent;
        LinkedList<Poi> pois;
        LinkedList<Road> roads;
        LinkedList<Roadinter> roadinters;
        LinkedList<Aoi> aois;

        public static class AddressComponent {
            String country;
            String province;
            List<String> city;
            int citycode;
            String district;
            int adcode;
            String township;
            long towncode;
            Building neighborhood;
            Building building;
            StreetNumber streetNumber;
            LinkedList<BusinessAreas> businessAreas;


            public static class Building {
                String name;
                String type;
            }

            public static class StreetNumber {
                String street;
                String number;
                String location;
                String direction;
                float distance;
            }

            public static class BusinessAreas {
                String location;
                String name;
                int id;
            }
        }

        public static class Poi{
            String id;
            String name;
            String type;
            String tel;
            String direction;
            String distance;
            String location;
            String address;
            String poiweight;
            String businessarea;
        }

        public static class Road {
            String id;
            String name;
            String direction;
            String distance;
            String location;
        }

        public static class Roadinter {
            String direction;
            float distance;
            String location;
            String first_id;
            String first_name;
            String second_id;
            String second_name;
        }

        public static class Aoi {
            String id;
            String name;
            int adcode;
            String location;
            String area;
            String distance;
            String type;
        }
    }
}
