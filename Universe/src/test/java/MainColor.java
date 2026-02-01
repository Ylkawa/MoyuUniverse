import com.nekoyu.Universe.Utils.ColorUtils;
import com.nekoyu.Universe.Utils.ImageUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.*;
import java.util.List;

public class MainColor {
    static class ColorPoint {
        double r, g, b;
        int cluster;

        ColorPoint(int rgb) {
            r = (rgb >> 16) & 0xff;
            g = (rgb >> 8) & 0xff;
            b = rgb & 0xff;
        }
    }

    public static void main(String[] args) throws Exception {
        String imageUrl = "https://q1.qlogo.cn/g?b=qq&nk=1697775835&s=1";

        BufferedImage img = ImageIO.read(new URL(imageUrl));
        if (img == null) {
            throw new RuntimeException("无法读取图片");
        }

        List<ColorPoint> points = sample(img, 10);
        List<ColorPoint> centers = kMeans(points, 5, 20);

        int[] count = new int[5];
        for (ColorPoint p : points) count[p.cluster]++;

        int main = 0;
        for (int i = 1; i < 5; i++)
            if (count[i] > count[main]) main = i;

        ColorPoint c = centers.get(main);

        Color mainColor = ImageUtils.getMainColor(new URL("https://q1.qlogo.cn/g?b=qq&nk=1697775835&s=1"));
        System.out.println(ColorUtils.fg(mainColor) + mainColor);
        String colorCode = String.format("\u001B[38;2;%d;%d;%dm", (int) c.r, (int) c.g, (int) c.b);
        System.out.printf("Main Color: " + colorCode + "#%02X%02X%02X%n",
                (int) c.r, (int) c.g, (int) c.b);
    }

    static List<ColorPoint> sample(BufferedImage img, int step) {
        List<ColorPoint> list = new ArrayList<>();
        for (int y = 0; y < img.getHeight(); y += step)
            for (int x = 0; x < img.getWidth(); x += step)
                list.add(new ColorPoint(img.getRGB(x, y)));
        return list;
    }

    static List<ColorPoint> kMeans(List<ColorPoint> pts, int k, int iter) {
        Random rand = new Random();
        List<ColorPoint> centers = new ArrayList<>();
        for (int i = 0; i < k; i++)
            centers.add(pts.get(rand.nextInt(pts.size())));

        for (int it = 0; it < iter; it++) {
            for (ColorPoint p : pts)
                p.cluster = nearest(p, centers);

            double[] sr = new double[k], sg = new double[k], sb = new double[k];
            int[] cnt = new int[k];

            for (ColorPoint p : pts) {
                int c = p.cluster;
                sr[c] += p.r;
                sg[c] += p.g;
                sb[c] += p.b;
                cnt[c]++;
            }

            for (int i = 0; i < k; i++) {
                if (cnt[i] == 0) continue;
                centers.set(i, new ColorPoint(
                        ((int)(sr[i]/cnt[i]) << 16) |
                                ((int)(sg[i]/cnt[i]) << 8) |
                                (int)(sb[i]/cnt[i])
                ));
            }
        }
        return centers;
    }

    static int nearest(ColorPoint p, List<ColorPoint> cs) {
        double min = Double.MAX_VALUE;
        int idx = 0;
        for (int i = 0; i < cs.size(); i++) {
            ColorPoint c = cs.get(i);
            double d = Math.pow(p.r - c.r, 2)
                    + Math.pow(p.g - c.g, 2)
                    + Math.pow(p.b - c.b, 2);
            if (d < min) {
                min = d;
                idx = i;
            }
        }
        return idx;
    }
}
