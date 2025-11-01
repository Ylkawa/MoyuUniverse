package com.nekoyu.Universe.API.MessageChannel.MessageField;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.nekoyu.AmapAPI.v3.AmapClient;
import com.nekoyu.AmapAPI.v3.geocode.RegeoRequest;
import com.nekoyu.AmapAPI.v3.geocode.RegeoResponse;
import com.nekoyu.Universe.Universe;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.RoundingMode;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ImageField extends FileField {
    private static final Logger logger = LoggerFactory.getLogger(ImageField.class);

    public ImageField(URL url) {
        super(url);
        description = "";
        super.type = "image";
    }

    /**
     * 跟无参的没什么区别，但是提供一个异步同步的选择
     * */
    @Override
    public void solve(AtomicBoolean flag) {
        solve();
        flag.set(true);
    }

    @Override
    public void solve() {
        if (isSolved) return;
        StringBuilder descriptionBuilder = new StringBuilder().append("[图片, \n");
        try {
            descriptionBuilder.append("内容描述: \n").append(Universe.pictureSolver.getDescription(url)).append("\n");
        } catch (Exception e) {
            descriptionBuilder.append("出错，解析失败");
        }
        Metadata metadata = getMetadata();
        if (metadata != null) { // 如果EXIF信息存在
            // 尝试解析EXIF信息
            descriptionBuilder.append("\nEXIF信息(部分):");
            // 设备制造商和型号
            ExifIFD0Directory ifd0Dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0Dir != null) {
                descriptionBuilder.append("\n设备制造商: ").append(ifd0Dir.getString(ExifIFD0Directory.TAG_MAKE));
                descriptionBuilder.append("\n设备型号: ").append(ifd0Dir.getString(ExifIFD0Directory.TAG_MODEL));
            }
            // 拍摄时间
            ExifSubIFDDirectory exifDir = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (exifDir != null && exifDir.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL) != null) {
                descriptionBuilder.append("\n拍摄时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(exifDir.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)));
            }
            // 经纬度
            GpsDirectory gpsDirectory = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            if (gpsDirectory != null) {
                GeoLocation location = gpsDirectory.getGeoLocation();
                if (location != null) {
                    double longitude = location.getLongitude();
                    double latitude = location.getLatitude();
                    descriptionBuilder.append("\n经度: ").append(longitude);
                    descriptionBuilder.append("\n纬度: ").append(latitude);
                    if (longitude != 0.0d && latitude != 0.0d) { // 排除被抹除掉位置信息的照片，这类照片不处理（实测鸿蒙4会把经纬度改成0而不是删掉这个字段）
                        String amapKey = System.getenv("AmapAPIKey");
                        if (amapKey != null && !amapKey.isEmpty()) {
                            DecimalFormat df = new DecimalFormat("0.000000");
                            df.setRoundingMode(RoundingMode.HALF_UP);

                            AmapClient amapClient = new AmapClient(amapKey);
                            RegeoRequest regeoRequest = new RegeoRequest();
                            regeoRequest.setExtensions("all");
                            regeoRequest.setRadius("300");
                            regeoRequest.setLocation(longitude + "," + latitude);
                            try {
                                RegeoResponse regeoResponse = amapClient.regeoRequest(regeoRequest);
                                RegeoResponse.Regeocode regeocode = regeoResponse.regeocode;
                                descriptionBuilder.append("\n地图位置: ").append(regeocode.formatted_address);
                                if (regeocode.roads != null) {
                                    descriptionBuilder.append("\n附近道路: ");
                                    for (var road : regeocode.roads) {
                                        descriptionBuilder.append(road.name).append("(").append(road.direction).append(", ").append(road.distance).append("m); ");
                                    }
                                }
                                if (regeocode.roadinters != null) {
                                    descriptionBuilder.append("\n附近路口: ");
                                    for (var roadInter : regeocode.roadinters) {
                                        descriptionBuilder.append(roadInter.first_name).append(roadInter.second_name).append("口").append("(").append(roadInter.direction).append(", ").append(roadInter.distance).append("m); ");
                                    }
                                }
                                if (regeocode.pois != null) {
                                    descriptionBuilder.append("\n附近兴趣点: ");
                                    for (var poi : regeocode.pois) {
                                        descriptionBuilder.append(poi.name).append("(").append(poi.direction).append(", ").append(poi.distance).append("m); ");
                                    }
                                }
                            } catch (Exception e) {
                                logger.error(e.getMessage(), e);
                            }
                        }
                    }
                }
            }
        }
        descriptionBuilder.append("\n]");
        description = descriptionBuilder.toString();
        isSolved = true;
    }

    public String getAsString() {
        if (isSolved) return description;
        else return "[图片]";
    }

    public Metadata getMetadata() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
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
