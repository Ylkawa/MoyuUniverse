package com.nekoyu.Universe.AIChat.graph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** 图标资源通过流读取，运行时不需要把 JAR 资源解压到磁盘。 */
public interface IconAssetSource {
    InputStream open(String name) throws IOException;
    default byte[] read(String name, int limit) throws IOException {
        try (InputStream input = open(name)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Icon asset exceeds size limit: " + name);
            return bytes;
        }
    }
    default Path filePath(String name) { throw new UnsupportedOperationException("Classpath asset has no file path"); }
    static void validateName(String name) throws IOException {
        if (name == null || !name.matches("[a-zA-Z0-9_./-]+") || name.startsWith("/")
                || name.contains("..") || name.contains("//")) throw new IOException("Invalid icon asset name");
    }
    static IconAssetSource directory(Path path) { return new Directory(path.toAbsolutePath().normalize()); }
    static IconAssetSource classpath(ClassLoader loader, String prefix) { return new Classpath(loader, prefix); }
    record Directory(Path root) implements IconAssetSource {
        @Override public InputStream open(String name) throws IOException {
            validateName(name);
            Path path = root.resolve(name).normalize();
            if (!path.startsWith(root) || !path.toRealPath().startsWith(root.toRealPath()))
                throw new IOException("Icon asset escapes directory");
            return Files.newInputStream(path);
        }
        @Override public Path filePath(String name) { return root.resolve(name); }
    }
    record Classpath(ClassLoader loader, String prefix) implements IconAssetSource {
        @Override public InputStream open(String name) throws IOException {
            validateName(name);
            InputStream input = loader.getResourceAsStream(prefix + name);
            if (input == null) throw new IOException("Missing bundled icon asset: " + name);
            return input;
        }
    }
}
