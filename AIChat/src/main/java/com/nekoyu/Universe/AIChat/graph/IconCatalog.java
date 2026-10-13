package com.nekoyu.Universe.AIChat.graph;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record IconCatalog(String library, String version, String digest, String preprocessingVersion,
                          IconAssetSource assets, String licenseName, List<IconResource> icons) {
    public IconCatalog {
        Objects.requireNonNull(library); Objects.requireNonNull(version); Objects.requireNonNull(digest);
        Objects.requireNonNull(preprocessingVersion); Objects.requireNonNull(assets); Objects.requireNonNull(licenseName);
        icons = List.copyOf(icons);
        if (icons.isEmpty() || icons.size() > 20_000) throw new IllegalArgumentException("Invalid icon count");
        var ids = new HashSet<String>();
        for (IconResource icon : icons) {
            if (!icon.library().equals(library) || !icon.libraryVersion().equals(version) || !ids.add(icon.id()))
                throw new IllegalArgumentException("Inconsistent or duplicate icon: " + icon.id());
        }
    }
    public IconCatalog(String library, String version, String digest, String preprocessingVersion,
                       Path license, List<IconResource> icons) {
        this(library, version, digest, preprocessingVersion, IconAssetSource.directory(license.toAbsolutePath().getParent()),
                license.getFileName().toString(), icons);
    }
    public Path licensePath() { return assets.filePath(licenseName); }
}
