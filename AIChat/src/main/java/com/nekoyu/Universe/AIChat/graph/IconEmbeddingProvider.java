package com.nekoyu.Universe.AIChat.graph;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Text and image outputs MUST use the same space and preserve input order. */
public interface IconEmbeddingProvider {
    record Space(String model, String revision, int dimension) {
        public Space {
            if (model == null || model.isBlank() || revision == null || revision.isBlank() || dimension <= 0)
                throw new IllegalArgumentException("Model, revision and positive dimension are required");
        }
    }
    record Image(String id, String mimeType, byte[] bytes) {
        public Image {
            Objects.requireNonNull(id); Objects.requireNonNull(mimeType);
            bytes = Objects.requireNonNull(bytes).clone();
            if (bytes.length == 0) throw new IllegalArgumentException("Image is empty");
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    Space space();
    List<double[]> embedTexts(List<String> texts) throws IOException;
    List<double[]> embedImages(List<Image> images) throws IOException;
    record TextResult(double[] vector) { public boolean available() { return vector != null; } }
    /** 缓存适配器可逐描述返回失败，避免一个新描述影响已缓存图标。 */
    default List<TextResult> textResults(List<String> texts) throws IOException {
        try { return Vectors.batch(embedTexts(texts), texts.size(), space().dimension()).stream().map(TextResult::new).toList(); }
        catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger(IconEmbeddingProvider.class).warn("图标文字向量计算失败，省略图标：{}", e.toString());
            return texts.stream().map(t -> new TextResult(null)).toList();
        }
    }
}
