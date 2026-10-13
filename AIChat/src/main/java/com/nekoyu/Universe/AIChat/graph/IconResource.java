package com.nekoyu.Universe.AIChat.graph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** 资源身份只取决于内容，目录和类加载器不参与身份比较。 */
public record IconResource(String id, String name, String library, String libraryVersion,
                           String sha256, String svgName, String previewName, String previewSha256, IconAssetSource assets) {
    public IconResource {
        Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(library);
        Objects.requireNonNull(libraryVersion); Objects.requireNonNull(sha256);
        Objects.requireNonNull(svgName); Objects.requireNonNull(previewName);
        Objects.requireNonNull(previewSha256); Objects.requireNonNull(assets);
    }
    public IconResource(String id, String name, String library, String version, String hash, Path svg, Path png) {
        this(id, name, library, version, hash, svg.getFileName().toString(), png.getFileName().toString(),
                previewHash(png), IconAssetSource.directory(png.toAbsolutePath().getParent()));
    }
    private static String previewHash(Path path) {
        try { return IndexDescriptor.hash(Files.readAllBytes(path)); }
        catch (IOException e) { throw new IllegalArgumentException("Cannot read icon preview", e); }
    }
    public InputStream openPreview() throws IOException { return assets.open(previewName); }
    public Path svgPath() { return assets.filePath(svgName); }
    public Path previewPath() { return assets.filePath(previewName); }
    @Override public boolean equals(Object other) {
        return other instanceof IconResource r && id.equals(r.id) && name.equals(r.name)
                && library.equals(r.library) && libraryVersion.equals(r.libraryVersion) && sha256.equals(r.sha256)
                && svgName.equals(r.svgName) && previewName.equals(r.previewName) && previewSha256.equals(r.previewSha256);
    }
    @Override public int hashCode() { return Objects.hash(id, name, library, libraryVersion, sha256, svgName, previewName, previewSha256); }
}
