package com.nekoyu.Universe.AIChat.graph;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;

/** 预建是显式操作；写入每个成功向量的缓存，完整包通过验证后才发布指针。 */
public final class IconBundleBuilder {
    public IconBundle build(IconCatalog catalog, IconEmbeddingProvider provider, Path cacheDirectory, Path output) throws IOException {
        return build(catalog, provider, cacheDirectory, output, 1);
    }
    public IconBundle build(IconCatalog catalog, IconEmbeddingProvider provider, Path cacheDirectory, Path output, int batchSize) throws IOException {
        if (batchSize < 1 || batchSize > 16) throw new IllegalArgumentException("Build batch size must be 1-16");
        IndexDescriptor descriptor = IndexDescriptor.of(provider.space(), catalog);
        var cache = new EmbeddingCache(cacheDirectory, provider.space(), 0);
        output = output.toAbsolutePath().normalize(); Files.createDirectories(output);
        Path stage = Files.createTempDirectory(output, ".build-");
        try {
            for (IconResource icon : catalog.icons()) {
                AtomicFiles.write(stage.resolve(icon.svgName()), icon.assets().read(icon.svgName(), LucideCatalogImporter.MEMBER_LIMIT));
                AtomicFiles.write(stage.resolve(icon.previewName()), icon.assets().read(icon.previewName(), LucideCatalogImporter.MEMBER_LIMIT));
            }
            AtomicFiles.write(stage.resolve("LICENSE"), catalog.assets().read(catalog.licenseName(), LucideCatalogImporter.MEMBER_LIMIT));
            AtomicFiles.write(stage.resolve("catalog.json"), catalog.assets().read("catalog.json", 8 * LucideCatalogImporter.MEMBER_LIMIT));
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IOException(e); }
            try (var stream = new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(Files.newOutputStream(stage.resolve("vectors.bin")), digest)))) {
                stream.writeInt(IconBundle.MAGIC); stream.writeInt(1); stream.writeInt(catalog.icons().size());
                stream.writeInt(provider.space().dimension()); stream.writeUTF(descriptor.fingerprint());
                int written = 0;
                for (int start = 0; start < catalog.icons().size(); start += batchSize) {
                    if (!descriptor.space().equals(provider.space())) throw new IOException("Provider identity changed during build");
                    var batch = catalog.icons().subList(start, Math.min(start + batchSize, catalog.icons().size()));
                    var vectors = new java.util.LinkedHashMap<String, double[]>();
                    var images = new java.util.ArrayList<IconEmbeddingProvider.Image>();
                    var missing = new java.util.ArrayList<IconResource>();
                    for (IconResource icon : batch) {
                        double[] value = cache.find(icon.previewSha256());
                        if (value != null) vectors.put(icon.previewSha256(), value);
                        else if (missing.stream().noneMatch(r -> r.previewSha256().equals(icon.previewSha256()))) {
                            missing.add(icon);
                            images.add(new IconEmbeddingProvider.Image(icon.id(), "image/png", icon.assets().read(icon.previewName(), LucideCatalogImporter.MEMBER_LIMIT)));
                        }
                    }
                    if (!images.isEmpty()) {
                        var generated = Vectors.batch(provider.embedImages(java.util.List.copyOf(images)), images.size(), descriptor.space().dimension());
                        for (int i = 0; i < missing.size(); i++) {
                            final double[] value = generated.get(i);
                            vectors.put(missing.get(i).previewSha256(), cache.get(missing.get(i).previewSha256(), () -> value));
                        }
                    }
                    for (IconResource icon : batch) {
                        stream.writeUTF(icon.id());
                        for (float value : Vectors.normalized(vectors.get(icon.previewSha256()), descriptor.space().dimension())) stream.writeFloat(value);
                        written++;
                    }
                    if (written % 50 < batchSize || written == catalog.icons().size()) System.out.println("Prepared " + written + "/" + catalog.icons().size() + " icons");
                }
            }
            String vectorHash = java.util.HexFormat.of().formatHex(digest.digest());
            String directory = "bundles/" + descriptor.fingerprint() + "-" + vectorHash.substring(0, 16) + "/";
            var current = new JsonObject(); current.addProperty("formatVersion", 1); current.addProperty("directory", directory);
            current.add("descriptor", new Gson().toJsonTree(descriptor)); current.addProperty("vectorsSha256", vectorHash);
            byte[] pointer = current.toString().getBytes(StandardCharsets.UTF_8);
            Path stagingRoot = Files.createTempDirectory(output, ".verify-");
            try {
                Files.createDirectories(stagingRoot.resolve(directory).getParent());
                Files.move(stage, stagingRoot.resolve(directory));
                AtomicFiles.write(stagingRoot.resolve("current.json"), pointer);
                IconBundle.load(IconAssetSource.directory(stagingRoot));
                Path destination = output.resolve(directory);
                Files.createDirectories(destination.getParent());
                if (!Files.exists(destination)) Files.move(stagingRoot.resolve(directory), destination, StandardCopyOption.ATOMIC_MOVE);
                // 再从实际目标验证，已有目录损坏时不覆盖有效指针。
                IconAssetSource target = name -> name.equals("current.json") ? new ByteArrayInputStream(pointer) : IconAssetSource.directory(outputRoot(destination, directory)).open(name);
                IconBundle bundle = IconBundle.load(target);
                AtomicFiles.write(output.resolve("current.json"), pointer);
                return bundle;
            } finally { removeStage(stagingRoot, output); }
        } finally { removeStage(stage, output); }
    }
    private static Path outputRoot(Path destination, String directory) {
        return destination.getParent().getParent();
    }
    private static void removeStage(Path path, Path output) throws IOException {
        if (!Files.exists(path)) return;
        if (!path.toAbsolutePath().normalize().getParent().equals(output.toAbsolutePath().normalize())
                || !(path.getFileName().toString().startsWith(".build-") || path.getFileName().toString().startsWith(".verify-")))
            throw new IOException("Refusing to clean unexpected build directory");
        // 只清理本次创建的临时目录，不删除此前已发布的包。
        try (var files = Files.walk(path)) {
            for (Path member : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(member);
        }
    }
}
