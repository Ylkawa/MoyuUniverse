package com.nekoyu.Universe.AIChat.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;

/** 显式导入固定版本的资源；运行时只读取清单，不下载或计算向量。 */
public final class LucideCatalogImporter {
    static final int MEMBER_LIMIT = 1024 * 1024;
    public IconCatalog loadManifest(Path file) throws IOException {
        return load(IconAssetSource.directory(file.toRealPath().getParent()), file.getFileName().toString());
    }
    public IconCatalog load(IconAssetSource source, String manifest) throws IOException {
        try {
            var json = JsonParser.parseString(new String(source.read(manifest, 8 * MEMBER_LIMIT), StandardCharsets.UTF_8)).getAsJsonObject();
            String version = json.get("version").getAsString(), license = json.get("license").getAsString();
            if (!"lucide".equals(json.get("library").getAsString())
                    || !SvgIconPreview.VERSION.equals(json.get("preprocessingVersion").getAsString())) throw new IOException("Unsupported icon catalog");
            byte[] licenseBytes = source.read(license, MEMBER_LIMIT);
            StringBuilder identity = identity(version, licenseBytes);
            var resources = new ArrayList<IconResource>();
            String previous = "";
            for (var raw : json.getAsJsonArray("icons")) {
                var item = raw.getAsJsonObject();
                String name = item.get("name").getAsString(), id = item.get("id").getAsString();
                if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || name.compareTo(previous) <= 0 || !id.equals("lucide/" + name))
                    throw new IOException("Invalid or unordered icon: " + name);
                previous = name;
                String svg = item.get("svg").getAsString(), png = item.get("preview").getAsString();
                byte[] svgBytes = source.read(svg, MEMBER_LIMIT), pngBytes = source.read(png, MEMBER_LIMIT);
                String svgHash = IndexDescriptor.hash(svgBytes), pngHash = IndexDescriptor.hash(pngBytes);
                if (!svgHash.equals(item.get("sha256").getAsString()) || !pngHash.equals(item.get("previewSha256").getAsString()))
                    throw new IOException("Icon content hash differs: " + id);
                verifyPng(pngBytes);
                identity.append(name).append(':').append(svgHash).append(':').append(pngHash).append('\n');
                resources.add(new IconResource(id, name, "lucide", version, svgHash, svg, png, pngHash, source));
                if (resources.size() > 20_000) throw new IOException("Icon catalog is too large");
            }
            String digest = IndexDescriptor.hash(identity.toString().getBytes(StandardCharsets.UTF_8));
            if (!digest.equals(json.get("digest").getAsString())) throw new IOException("Icon catalog digest differs");
            return new IconCatalog("lucide", version, digest, SvgIconPreview.VERSION, source, license, resources);
        } catch (RuntimeException e) { throw new IOException("Invalid icon catalog manifest", e); }
    }
    static void verifyPng(byte[] bytes) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Invalid icon PNG");
            var reader = readers.next();
            try {
                reader.setInput(input);
                if (!"png".equalsIgnoreCase(reader.getFormatName()) || reader.getWidth(0) != 256 || reader.getHeight(0) != 256)
                    throw new IOException("Expected 256x256 PNG preview");
                reader.read(0).flush();
            } finally { reader.dispose(); }
        }
    }
    private static StringBuilder identity(String version, byte[] license) {
        return new StringBuilder("lucide\n").append(version).append('\n').append(SvgIconPreview.VERSION)
                .append('\n').append(IndexDescriptor.hash(license)).append('\n');
    }
    public IconCatalog importDirectory(Path directory, String version, Path storage) throws IOException {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Explicit Lucide version required");
        var source = IconAssetSource.directory(directory);
        byte[] license = source.read("LICENSE", MEMBER_LIMIT);
        try {
            var metadata = JsonParser.parseString(new String(source.read("package.json", MEMBER_LIMIT), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!metadata.get("name").getAsString().equals("lucide-static") || !metadata.get("version").getAsString().equals(version))
                throw new IOException("Lucide package name/version mismatch");
        } catch (RuntimeException e) { throw new IOException("Invalid Lucide package.json", e); }
        Path icons = Files.isDirectory(directory.resolve("icons")) ? directory.resolve("icons") : directory;
        java.util.List<Path> paths;
        try (var stream = Files.list(icons)) {
            paths = stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".svg"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().replaceFirst("\\.svg$", ""))).toList();
        }
        if (paths.isEmpty() || paths.size() > 20_000) throw new IOException("Invalid Lucide icon count");
        var preview = new SvgIconPreview();
        var entries = new ArrayList<byte[]>();
        var names = new ArrayList<String>();
        var manifestIcons = new JsonArray();
        var identity = identity(version, license);
        for (Path path : paths) {
            String name = path.getFileName().toString().replaceFirst("\\.svg$", "");
            if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw new IOException("Invalid icon name");
            if (Files.size(path) > MEMBER_LIMIT || !path.toRealPath().startsWith(directory.toRealPath())) throw new IOException("Invalid SVG member");
            byte[] svg = Files.readAllBytes(path), png = preview.render(svg);
            verifyPng(png);
            String hash = IndexDescriptor.hash(svg), pngHash = IndexDescriptor.hash(png);
            identity.append(name).append(':').append(hash).append(':').append(pngHash).append('\n');
            names.add(name); entries.add(svg); entries.add(png);
            var item = new JsonObject(); item.addProperty("id", "lucide/" + name); item.addProperty("name", name);
            item.addProperty("sha256", hash); item.addProperty("previewSha256", pngHash);
            item.addProperty("svg", name + ".svg"); item.addProperty("preview", name + ".png"); manifestIcons.add(item);
        }
        String digest = IndexDescriptor.hash(identity.toString().getBytes(StandardCharsets.UTF_8));
        Path destination = storage.toAbsolutePath().resolve("lucide").resolve(digest);
        Files.createDirectories(destination);
        for (int i = 0; i < names.size(); i++) {
            AtomicFiles.write(destination.resolve(names.get(i) + ".svg"), entries.get(i * 2));
            AtomicFiles.write(destination.resolve(names.get(i) + ".png"), entries.get(i * 2 + 1));
        }
        AtomicFiles.write(destination.resolve("LICENSE"), license);
        var json = new JsonObject(); json.addProperty("library", "lucide"); json.addProperty("version", version);
        json.addProperty("digest", digest); json.addProperty("preprocessingVersion", SvgIconPreview.VERSION);
        json.addProperty("license", "LICENSE"); json.add("icons", manifestIcons);
        AtomicFiles.write(destination.resolve("catalog.json"), json.toString().getBytes(StandardCharsets.UTF_8));
        return loadManifest(destination.resolve("catalog.json"));
    }
}
