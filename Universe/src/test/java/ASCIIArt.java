import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class ASCIIArt {
    private static final String CHARS = "@#&$%*o!;:. ";

    public static void main(String[] args) throws IOException {
        BufferedImage image = ImageIO.read(new File("C:\\Users\\imylk\\Pictures\\e76fc676b58f23c6bd9161723f12da00c7e051c5.jpg@240w_240h_1c_1s_!web-avatar-space-header.png"));
        // 调整目标宽度
        int targetWidth = 100;
        // 按比例计算高度（注意字符高度比）
        int targetHeight = (int) ((double) image.getHeight() / image.getWidth() * targetWidth * 0.5);

        // 缩放图片
        Image scaled = image.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.drawImage(scaled, 0, 0, null);
        g2d.dispose();

        // 生成彩色 ASCII
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < resized.getHeight(); y++) {
            for (int x = 0; x < resized.getWidth(); x++) {
                int rgb = resized.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;

                int gray = (r + g + b) / 3;
                int index = (gray * (CHARS.length() - 1)) / 255;
                char c = CHARS.charAt(index);

                sb.append(String.format("\u001B[38;2;%d;%d;%dm%c", r, g, b, c));
            }
            sb.append("\u001B[0m\n");
        }
        System.out.println(sb);
    }
}
