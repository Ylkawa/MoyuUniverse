package com.nekoyu.Universe.AIChat.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Imports an explicitly provided lucide-static directory (icons/ and LICENSE). No network calls. */
public final class LucideCatalogImporter {
    private record Source(String name, byte[] svg, String hash) {}
    private final SvgIconPreview preview = new SvgIconPreview();

    /** Loads a previously imported catalog without rendering previews or rebuilding vectors. */
    public IconCatalog loadManifest(Path manifestFile) throws IOException {
        Path manifest = manifestFile.toRealPath();
        Path directory = manifest.getParent();
        try {
            if (Files.size(manifest) > 8 * 1024 * 1024) throw new IOException("Icon manifest is too large");
            var json = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
            String version = json.get("version").getAsString();
            if (!"lucide".equals(json.get("library").getAsString())
                    || !SvgIconPreview.VERSION.equals(json.get("preprocessingVersion").getAsString()))
                throw new IOException("Unsupported icon catalog or preview preprocessing version");
            Path license = member(directory, json.get("license").getAsString());
            StringBuilder identity = new StringBuilder("lucide\n").append(version).append('\n').append(SvgIconPreview.VERSION)
                    .append('\n').append(IndexDescriptor.hash(boundedBytes(license))).append('\n');
            List<IconResource> resources = new ArrayList<>();
            String previous = "";
            for (var entry : json.getAsJsonArray("icons")) {
                var item = entry.getAsJsonObject();
                String name = item.get("name").getAsString(), id = item.get("id").getAsString();
                if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || name.compareTo(previous) <= 0 || !id.equals("lucide/" + name))
                    throw new IOException("Invalid or unordered catalog icon: " + name);
                previous = name;
                Path svg = member(directory, item.get("svg").getAsString());
                Path png = member(directory, item.get("preview").getAsString());
                String hash = IndexDescriptor.hash(boundedBytes(svg));
                if (!hash.equals(item.get("sha256").getAsString())) throw new IOException("Icon source hash differs: " + id);
                identity.append(name).append(':').append(hash).append('\n');
                resources.add(new IconResource(id, name, "lucide", version, hash, svg, png));
                if (resources.size() > 20_000) throw new IOException("Icon catalog is too large");
            }
            String digest = IndexDescriptor.hash(identity.toString().getBytes(StandardCharsets.UTF_8));
            if (!digest.equals(json.get("digest").getAsString())) throw new IOException("Icon catalog digest differs");
            return new IconCatalog("lucide", version, digest, SvgIconPreview.VERSION, license, resources);
        } catch (RuntimeException e) { throw new IOException("Invalid icon catalog manifest", e); }
    }
    private static byte[] boundedBytes(Path path) throws IOException {
        if (Files.size(path) > 1024 * 1024) throw new IOException("Icon catalog member exceeds 1 MiB");
        return Files.readAllBytes(path);
    }
    private static Path member(Path directory, String name) throws IOException {
        Path path = directory.resolve(name).normalize();
        if (Path.of(name).isAbsolute() || !path.startsWith(directory) || !Files.isRegularFile(path)
                || !path.toRealPath().startsWith(directory)) throw new IOException("Invalid icon catalog member path");
        return path.toRealPath();
    }

    public IconCatalog importDirectory(Path sourceDirectory, String version, Path cacheDirectory) throws IOException {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("Explicit Lucide version is required");
        Path source = sourceDirectory.toAbsolutePath().normalize();
        Path license = source.resolve("LICENSE");
        if (!Files.isRegularFile(license)) throw new IOException("Lucide LICENSE is required: " + license);
        byte[] licenseBytes = Files.readAllBytes(license);
        Path packageJson = source.resolve("package.json");
        if (Files.exists(packageJson)) {
            try {
                var metadata = JsonParser.parseString(Files.readString(packageJson)).getAsJsonObject();
                if (!metadata.get("name").getAsString().equals("lucide-static") || !metadata.get("version").getAsString().equals(version))
                    throw new IOException("Lucide package name/version does not match requested import");
            } catch (RuntimeException e) { throw new IOException("Invalid Lucide package.json", e); }
        }
        Path iconDirectory = Files.isDirectory(source.resolve("icons")) ? source.resolve("icons") : source;
        List<Path> paths;
        try (var files = Files.list(iconDirectory)) {
            paths = files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".svg"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        }
        if (paths.isEmpty()) throw new IOException("No Lucide SVG icons found");
        List<Source> sources = new ArrayList<>();
        StringBuilder identity = new StringBuilder("lucide\n").append(version).append('\n').append(SvgIconPreview.VERSION).append('\n')
                .append(IndexDescriptor.hash(licenseBytes)).append('\n');
        for (Path path : paths) {
            String file = path.getFileName().toString();
            String name = file.substring(0, file.length() - 4);
            if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw new IOException("Invalid Lucide icon filename: " + file);
            byte[] svg = Files.readAllBytes(path);
            String hash = IndexDescriptor.hash(svg);
            sources.add(new Source(name, svg, hash));
            identity.append(name).append(':').append(hash).append('\n');
        }
        String digest = IndexDescriptor.hash(identity.toString().getBytes(StandardCharsets.UTF_8));
        Path destination = cacheDirectory.toAbsolutePath().normalize().resolve("lucide").resolve(digest);
        Files.createDirectories(destination);
        List<IconResource> resources = new ArrayList<>();
        JsonArray icons = new JsonArray();
        for (Source entry : sources) {
            Path svg = destination.resolve(entry.name() + ".svg");
            Path png = destination.resolve(entry.name() + ".png");
            byte[] pngBytes = preview.render(entry.svg());
            writeIfChanged(svg, entry.svg()); writeIfChanged(png, pngBytes);
            resources.add(new IconResource("lucide/" + entry.name(), entry.name(), "lucide", version, entry.hash(), svg, png));
            JsonObject item = new JsonObject(); item.addProperty("id", "lucide/" + entry.name());
            item.addProperty("name", entry.name()); item.addProperty("sha256", entry.hash());
            item.addProperty("svg", svg.getFileName().toString()); item.addProperty("preview", png.getFileName().toString()); icons.add(item);
        }
        Path storedLicense = destination.resolve("LICENSE"); writeIfChanged(storedLicense, licenseBytes);
        JsonObject manifest = new JsonObject(); manifest.addProperty("library", "lucide"); manifest.addProperty("version", version);
        manifest.addProperty("digest", digest); manifest.addProperty("preprocessingVersion", SvgIconPreview.VERSION);
        manifest.addProperty("license", "LICENSE"); manifest.add("icons", icons);
        writeIfChanged(destination.resolve("catalog.json"), manifest.toString().getBytes(StandardCharsets.UTF_8));
        return new IconCatalog("lucide", version, digest, SvgIconPreview.VERSION, storedLicense, resources);
    }
    private static void writeIfChanged(Path path, byte[] bytes) throws IOException {
        if (Files.isRegularFile(path) && java.util.Arrays.equals(Files.readAllBytes(path), bytes)) return;
        Path temporary = Files.createTempFile(path.getParent(), "import-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
