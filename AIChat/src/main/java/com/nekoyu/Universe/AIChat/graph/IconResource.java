package com.nekoyu.Universe.AIChat.graph;

import java.nio.file.Path;
import java.util.Objects;

/** Durable library resources, not chat ImageFields whose files can be collected. */
public record IconResource(String id, String name, String library, String libraryVersion,
                           String sha256, Path svgPath, Path previewPath) {
    public IconResource {
        Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(library);
        Objects.requireNonNull(libraryVersion); Objects.requireNonNull(sha256);
        svgPath = Objects.requireNonNull(svgPath).toAbsolutePath().normalize();
        previewPath = Objects.requireNonNull(previewPath).toAbsolutePath().normalize();
    }
}
