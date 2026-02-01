package com.nekoyu.Universe.Utils;

import java.awt.Color;

public class ColorUtils {
    // 重置
    public static final String RESET = "\u001B[0m";

    // 前景色
    public static String fg(Color c) {
        return String.format("\u001B[38;2;%d;%d;%dm",
                c.getRed(), c.getGreen(), c.getBlue());
    }

    // 背景色
    public static String bg(Color c) {
        return String.format("\u001B[48;2;%d;%d;%dm",
                c.getRed(), c.getGreen(), c.getBlue());
    }
}
