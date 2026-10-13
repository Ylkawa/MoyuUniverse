package com.nekoyu.Universe.AIChat.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** All inputs that can change the meaning of indexed vectors form part of the identity. */
public record IndexDescriptor(IconEmbeddingProvider.Space space, String library, String libraryVersion,
                              String catalogDigest, String preprocessingVersion) {
    public IndexDescriptor {
        Objects.requireNonNull(space); Objects.requireNonNull(library); Objects.requireNonNull(libraryVersion);
        Objects.requireNonNull(catalogDigest); Objects.requireNonNull(preprocessingVersion);
    }
    public static IndexDescriptor of(IconEmbeddingProvider.Space space, IconCatalog catalog) {
        return new IndexDescriptor(space, catalog.library(), catalog.version(), catalog.digest(), catalog.preprocessingVersion());
    }
    public String fingerprint() {
        // Length-prefix fields so delimiters in model/revision names cannot create collisions.
        StringBuilder value = new StringBuilder();
        for (String field : new String[]{space.model(), space.revision(), Integer.toString(space.dimension()),
                library, libraryVersion, catalogDigest, preprocessingVersion}) value.append(field.length()).append(':').append(field);
        return hash(value.toString().getBytes(StandardCharsets.UTF_8));
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
