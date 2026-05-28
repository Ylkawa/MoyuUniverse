package com.nekoyu.Universe.Utils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class Time {
    public static String formatTimestamp(long timestamp) {
        return Instant.ofEpochMilli(timestamp)  // 转 Instant
                .atZone(ZoneId.systemDefault()) // 转本地时区
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
