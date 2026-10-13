package com.nekoyu.Universe.AIChat.graph;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record IconCatalog(String library, String version, String digest, String preprocessingVersion,
                          Path licensePath, List<IconResource> icons) {
    public IconCatalog {
        Objects.requireNonNull(library); Objects.requireNonNull(version); Objects.requireNonNull(digest);
        Objects.requireNonNull(preprocessingVersion); licensePath = Objects.requireNonNull(licensePath).toAbsolutePath().normalize();
        icons = List.copyOf(icons);
        if (icons.isEmpty()) throw new IllegalArgumentException("Catalog contains no icons");
        var ids = new HashSet<String>();
        for (IconResource icon : icons) {
            if (!icon.library().equals(library) || !icon.libraryVersion().equals(version) || !ids.add(icon.id()))
                throw new IllegalArgumentException("Inconsistent or duplicate icon: " + icon.id());
        }
    }
}
