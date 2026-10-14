package com.nekoyu.Universe.AIChat.graph;

import java.net.URI;
import java.util.Set;
import java.util.regex.Pattern;

/** 兼容旧包中误写入工作空间 URL 的身份，只去掉路由信息，保留模型及全部编码参数。 */
final class EmbeddingSpaces {
    private static final Pattern LEGACY = Pattern.compile("^(dashscope-(?:independent|fused)-v1)@(https?://[^#]+)(#space=.+#res=[0-3])$");
    private static final Set<String> MODELS = Set.of("qwen3-vl-embedding",
            "tongyi-embedding-vision-plus-2026-03-06", "tongyi-embedding-vision-flash-2026-03-06");
    private EmbeddingSpaces() {}
    static IconEmbeddingProvider.Space canonical(IconEmbeddingProvider.Space space) {
        if (!MODELS.contains(space.model())) return space;
        var match = LEGACY.matcher(space.revision());
        if (!match.matches()) return space;
        try {
            var uri = URI.create(match.group(2));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || !uri.getPath().endsWith("/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding")) return space;
        } catch (IllegalArgumentException e) { return space; }
        return new IconEmbeddingProvider.Space(space.model(), match.group(1) + match.group(3), space.dimension());
    }
}
