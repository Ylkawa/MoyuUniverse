package com.nekoyu.Universe.Utils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.*;

public class ImageUtils {
    public static Color getMainColor(URL imageUrl) throws IOException {
        BufferedImage img = ImageIO.read(imageUrl);
        if (img == null) {
            throw new IllegalArgumentException("URL 不是有效图片");
        }

        int step = Math.max(1, Math.min(img.getWidth(), img.getHeight()) / 50);

        List<int[]> pixels = new ArrayList<>();
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                int rgb = img.getRGB(x, y);
                Color c = new Color(rgb, true);

                // 过滤透明 / 极端黑白
                if (c.getAlpha() < 200) continue;
                if (c.getRed() < 15 && c.getGreen() < 15 && c.getBlue() < 15) continue;
                if (c.getRed() > 240 && c.getGreen() > 240 && c.getBlue() > 240) continue;

                pixels.add(new int[]{c.getRed(), c.getGreen(), c.getBlue()});
            }
        }

        if (pixels.isEmpty()) {
            return Color.GRAY;
        }

        return kMeansMainColor(pixels, 3, 15);
    }

    // ---- K-Means ----

    private static Color kMeansMainColor(List<int[]> pts, int k, int iterations) {
        Random rand = new Random(24);
        int[][] centers = new int[k][3];

        for (int i = 0; i < k; i++) {
            centers[i] = pts.get(rand.nextInt(pts.size()));
        }

        int[] labels = new int[pts.size()];

        for (int it = 0; it < iterations; it++) {
            for (int i = 0; i < pts.size(); i++) {
                labels[i] = nearest(pts.get(i), centers);
            }

            int[] cnt = new int[k];
            int[][] sum = new int[k][3];

            for (int i = 0; i < pts.size(); i++) {
                int c = labels[i];
                cnt[c]++;
                sum[c][0] += pts.get(i)[0];
                sum[c][1] += pts.get(i)[1];
                sum[c][2] += pts.get(i)[2];
            }

            for (int i = 0; i < k; i++) {
                if (cnt[i] == 0) continue;
                centers[i][0] = sum[i][0] / cnt[i];
                centers[i][1] = sum[i][1] / cnt[i];
                centers[i][2] = sum[i][2] / cnt[i];
            }
        }

        // 选像素最多的簇
        int[] count = new int[k];
        for (int label : labels) count[label]++;

        int main = 0;
        for (int i = 1; i < k; i++) {
            if (count[i] > count[main]) main = i;
        }

        return new Color(
                centers[main][0],
                centers[main][1],
                centers[main][2]
        );
    }

    private static int nearest(int[] p, int[][] centers) {
        double min = Double.MAX_VALUE;
        int idx = 0;
        for (int i = 0; i < centers.length; i++) {
            int[] c = centers[i];
            double d = Math.pow(p[0] - c[0], 2)
                    + Math.pow(p[1] - c[1], 2)
                    + Math.pow(p[2] - c[2], 2);
            if (d < min) {
                min = d;
                idx = i;
            }
        }
        return idx;
    }
}
