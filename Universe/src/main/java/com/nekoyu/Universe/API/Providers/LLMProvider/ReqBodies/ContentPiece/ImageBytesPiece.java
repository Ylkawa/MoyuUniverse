package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece;

import java.util.Objects;

/** 内存图片输入；编码及上传由 Provider 负责，不触发消息图片的文件托管。 */
public final class ImageBytesPiece extends ContentPiece {
    private final String mimeType;
    private final byte[] bytes;
    public ImageBytesPiece(String mimeType, byte[] bytes) {
        type = "image_bytes";
        this.mimeType = Objects.requireNonNull(mimeType);
        this.bytes = Objects.requireNonNull(bytes).clone();
        if (bytes.length == 0) throw new IllegalArgumentException("Image is empty");
    }
    public String getMimeType() { return mimeType; }
    public byte[] getBytes() { return bytes.clone(); }
}
