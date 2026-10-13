package com.nekoyu.universe.aichat.image_edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nekoyu.Universe.AIChat.XmlMarks;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.concurrent.TimeUnit;

public final class ImageEditService {
    static final int MAX_BYTES = 20 * 1024 * 1024;
    static final int MAX_DIMENSION = 8192;
    static final long MAX_PIXELS = 16_000_000;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageEditService.class);
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build();

    @FunctionalInterface
    interface ImageLoader { BufferedImage load(String source) throws IOException; }
    @FunctionalInterface
    interface ImagePublisher { PublishedImage publish(BufferedImage image) throws IOException; }
    record PublishedImage(String url, String token, ImageField image) {}

    private final ImageLoader loader;
    private final ImagePublisher publisher;

    ImageEditService(ImagePublisher publisher) {
        this(ImageEditService::download, publisher);
    }

    ImageEditService(ImageLoader loader, ImagePublisher publisher) {
        this.loader = loader;
        this.publisher = publisher;
    }

    public Message edit(String action, JsonElement args) {
        try {
            JsonObject fields = object(args, "参数");
            String source = source(fields.get("source_image"));
            BigDecimal x = null, y = null, width = null, height = null, angle = null;
            if ("crop".equals(action)) {
                JsonObject position = object(fields.get("top_left_position"), "top_left_position");
                x = percent(position.get("x"), "x", true);
                y = percent(position.get("y"), "y", true);
                width = percent(fields.get("width"), "width", false);
                height = percent(fields.get("height"), "height", false);
                if (x.add(width).compareTo(HUNDRED) > 0 || y.add(height).compareTo(HUNDRED) > 0)
                    throw new IllegalArgumentException("裁切区域不能超出原图，x+width 和 y+height 必须不超过100");
            } else if ("rotate".equals(action)) {
                angle = number(fields.get("angle"), "angle").remainder(BigDecimal.valueOf(360));
            } else {
                throw new IllegalArgumentException("未知图片编辑操作");
            }
            BufferedImage input = loader.load(source);
            if (input == null) throw new IOException("无法解码图片，支持 PNG/JPEG/GIF 等 Java ImageIO 可读取的格式");
            checkDimensions(input.getWidth(), input.getHeight());
            BufferedImage output = "crop".equals(action)
                    ? crop(input, x, y, width, height) : rotate(input, angle.doubleValue());
            PublishedImage published = publisher.publish(output);
            Message result = new Message("[图片编辑 / " + ("crop".equals(action) ? "裁切" : "旋转") + "]\n"
                    + "处理成功，PNG 尺寸：" + output.getWidth() + "×" + output.getHeight() + " 像素\n"
                    + "结果 URL：" + published.url() + "\n发送图片：" + XmlMarks.marker("image_edit", "id", published.token()));
            result.retainedFiles.add(published.image());
            return result;
        } catch (IllegalArgumentException error) {
            return new Message("图片编辑参数错误：" + error.getMessage());
        } catch (IOException error) {
            LOGGER.warn("图片编辑失败", error);
            return new Message("图片编辑失败：" + error.getMessage());
        }
    }

    private static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException(name + " 必须为对象");
        return value.getAsJsonObject();
    }

    private static BigDecimal number(JsonElement value, String name) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(name + " 必须为有限数值");
        try {
            BigDecimal result = value.getAsBigDecimal();
            if (!Double.isFinite(result.doubleValue()) || result.precision() > 100 || Math.abs((long) result.scale()) > 100)
                throw new NumberFormatException();
            return result;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " 必须为有限数值");
        }
    }

    private static BigDecimal percent(JsonElement value, String name, boolean position) {
        BigDecimal result = number(value, name);
        if (position ? result.signum() < 0 || result.compareTo(HUNDRED) >= 0
                : result.signum() <= 0 || result.compareTo(HUNDRED) > 0)
            throw new IllegalArgumentException(name + (position ? " 必须在 [0,100) 范围内" : " 必须在 (0,100] 范围内"));
        return result;
    }

    private static String source(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("source_image 必须为图片 URL 字符串");
        String source = value.getAsString().trim();
        try {
            URI uri = URI.create(source);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("source_image 必须为有效 HTTP/HTTPS 图片 URL；聊天图片请使用 <ref id=\"N\"/> 引用");
        }
        return source;
    }

    static void checkDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_PIXELS)
            throw new IOException("图片尺寸超出限制：最多1600万像素，单边最多8192像素");
    }

    static BufferedImage download(String source) throws IOException {
        try (var response = HTTP.newCall(new Request.Builder().url(source).build()).execute()) {
            if (!response.isSuccessful()) throw new IOException("图片下载失败，HTTP " + response.code());
            if (response.body() == null) throw new IOException("图片响应为空");
            if (response.body().contentLength() > MAX_BYTES) throw new IOException("图片文件超过20MiB限制");
            byte[] bytes = response.body().byteStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("图片文件超过20MiB限制");
            return decode(bytes);
        }
    }

    static BufferedImage decode(byte[] bytes) throws IOException {
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("响应不是受支持的图片格式");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                // 先检查图片头，再分配完整的像素缓冲区。
                checkDimensions(reader.getWidth(0), reader.getHeight(0));
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private static int pixel(BigDecimal percent, int size, RoundingMode rounding) {
        return percent.multiply(BigDecimal.valueOf(size)).divide(HUNDRED, 0, rounding).intValueExact();
    }

    private static BufferedImage crop(BufferedImage input, BigDecimal x, BigDecimal y,
                                       BigDecimal width, BigDecimal height) {
        int left = pixel(x, input.getWidth(), RoundingMode.FLOOR);
        int top = pixel(y, input.getHeight(), RoundingMode.FLOOR);
        int right = pixel(x.add(width), input.getWidth(), RoundingMode.CEILING);
        int bottom = pixel(y.add(height), input.getHeight(), RoundingMode.CEILING);
        BufferedImage output = new BufferedImage(right - left, bottom - top, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(input, 0, 0, output.getWidth(), output.getHeight(), left, top, right, bottom, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static BufferedImage rotate(BufferedImage input, double angle) throws IOException {
        double radians = Math.toRadians(angle);
        double cos = Math.abs(Math.cos(radians)), sin = Math.abs(Math.sin(radians));
        int width = (int) Math.ceil(input.getWidth() * cos + input.getHeight() * sin - 1e-10);
        int height = (int) Math.ceil(input.getHeight() * cos + input.getWidth() * sin - 1e-10);
        checkDimensions(width, height);
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            AffineTransform transform = new AffineTransform();
            transform.translate(width / 2.0, height / 2.0);
            transform.rotate(radians);
            transform.translate(-input.getWidth() / 2.0, -input.getHeight() / 2.0);
            graphics.drawImage(input, transform, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }
}
