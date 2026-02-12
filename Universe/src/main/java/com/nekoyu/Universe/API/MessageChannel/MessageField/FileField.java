package com.nekoyu.Universe.API.MessageChannel.MessageField;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Cleaner;
import java.net.URL;
import java.util.UUID;

public class FileField extends MsgField {
    String description;
    private static final Cleaner cleaner = Cleaner.create();
    private transient File file = null;
    public URL url;

    public FileField(URL url) {
        this.url = url;
        super.type = "file";
        cleaner.register(this, () -> {
            if (file != null && file.exists()) file.delete();
        });
    }

    public File getFile() throws IOException {
        if (file != null) return file;
        // 下载文件
        OkHttpClient httpClient = new OkHttpClient();
        Request request = new Request.Builder()
                .url(url)
                .build();
        File download = new File("./cache/" + UUID.randomUUID());
        try (Response resp = httpClient.newCall(request).execute()) {
            if (resp.isSuccessful()) try (InputStream inputStream = resp.body().byteStream(); FileOutputStream outputStream = new FileOutputStream(download)) {
                byte[] buffer = new byte[4096]; // Buffer for reading data
                int bytesRead;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            } else {
                throw new IOException("Req Fail with code" + resp.code());
            }
        }
        file = download;
        return file;
    }

    @Override
    public String getAsString() {
        return "[文件]";
    }

    public URL getUrl() {
        return url;
    }

    public void setUrl(URL url) {
        this.url = url;
    }
}
