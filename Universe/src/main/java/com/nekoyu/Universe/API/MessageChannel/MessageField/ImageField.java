package com.nekoyu.Universe.API.MessageChannel.MessageField;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.nekoyu.Universe.Universe;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.concurrent.TimeUnit;

public class ImageField extends FileField {
    private String description;

    public ImageField(URL url) {
        super(url);
        description = "";
        super.type = "image";
    }

    public String getAsString() {
        if (!description.isEmpty()) return description;
        if (Universe.pictureSolver != null) {
            synchronized (description) {
                if (!description.isEmpty()) return description;
                try {
                    StringBuilder descriptionBuilder = new StringBuilder();
                    descriptionBuilder.append("[图片, 内容描述: \n").append(Universe.pictureSolver.getDescription(url)).append("\n");
                    Metadata metadata = getMetadata();
                    if (metadata != null) { // 如果EXIF信息存在
                        // 尝试解析EXIF信息
                        descriptionBuilder.append("\nEXIF信息(部分):");
                        // 设备制造商和型号
                        ExifIFD0Directory ifd0Dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
                        if (ifd0Dir != null) {
                            descriptionBuilder.append("设备制造商: ").append(ifd0Dir.getString(ExifIFD0Directory.TAG_MAKE));
                            descriptionBuilder.append("设备型号: ").append(ifd0Dir.getString(ExifIFD0Directory.TAG_MODEL));
                        }
                        // 拍摄时间
                        ExifSubIFDDirectory exifDir = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
                        if (exifDir != null && exifDir.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL) != null) {
                            descriptionBuilder.append("拍摄时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(exifDir.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)));
                        }
                        // 经纬度
                        GpsDirectory gpsDirectory = metadata.getFirstDirectoryOfType(GpsDirectory.class);
                        if (gpsDirectory != null) {
                            GeoLocation location = gpsDirectory.getGeoLocation();
                            if (location != null) {
                                descriptionBuilder.append("经度: ").append(location.getLongitude());
                                descriptionBuilder.append("纬度: ").append(location.getLatitude());
                            }
                        }
                        descriptionBuilder.append("\n");
                    }
                    descriptionBuilder.append("]");
                    description = descriptionBuilder.toString();
                    return description;
                } catch (IOException e) {
                    description = "[图片]";
                    return "[图片]";
                }
            }
        } else return "[图片]";
    }

    public Metadata getMetadata() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        Request request;
        request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }

            // 直接使用响应体的流读取元数据
            return ImageMetadataReader.readMetadata(response.body().byteStream());
        } catch (IOException | ImageProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
