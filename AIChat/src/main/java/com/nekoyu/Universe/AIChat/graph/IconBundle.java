package com.nekoyu.Universe.AIChat.graph;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/** 预建包的内容和向量共同校验，加载完成后才交给运行时。 */
public record IconBundle(IconCatalog catalog, LocalIconIndex index, IconEmbeddingProvider.Space storedSpace) {
    public IconBundle(IconCatalog catalog, LocalIconIndex index) { this(catalog, index, index.descriptor().space()); }
    static final int MAGIC = 0x47495631;
    public static final String PREFIX = "graph/icons/";
    public static IconBundle load(IconAssetSource root) throws IOException {
        try {
            var manifest = JsonParser.parseString(new String(root.read("current.json", 32 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
            if (manifest.get("formatVersion").getAsInt() != 1) throw new IOException("Unsupported icon bundle format");
            String directory = manifest.get("directory").getAsString();
            if (!directory.matches("bundles/[a-f0-9]{64}-[a-f0-9]{16}/")) throw new IOException("Invalid bundle directory");
            IndexDescriptor descriptor = new Gson().fromJson(manifest.get("descriptor"), IndexDescriptor.class);
            IconAssetSource assets = name -> root.open(directory + name);
            IconCatalog catalog = new LucideCatalogImporter().load(assets, "catalog.json");
            if (!descriptor.equals(IndexDescriptor.of(descriptor.space(), catalog))) throw new IOException("Bundle identity mismatch");
            int dimension = descriptor.space().dimension(), count = catalog.icons().size();
            if (dimension > 4096 || (long) count * dimension * 4 > 128L * 1024 * 1024) throw new IOException("Icon matrix exceeds supported bounds");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            float[] matrix = new float[Math.multiplyExact(count, dimension)];
            try (var stream = new DataInputStream(new BufferedInputStream(new DigestInputStream(assets.open("vectors.bin"), digest)))) {
                if (stream.readInt() != MAGIC || stream.readInt() != 1 || stream.readInt() != count
                        || stream.readInt() != dimension || !stream.readUTF().equals(descriptor.fingerprint())) throw new IOException("Invalid vector header");
                for (int row = 0; row < count; row++) {
                    if (!stream.readUTF().equals(catalog.icons().get(row).id())) throw new IOException("Vector resource order differs");
                    for (int col = 0; col < dimension; col++) matrix[row * dimension + col] = stream.readFloat();
                }
                if (stream.read() != -1) throw new IOException("Unexpected vector data");
            }
            if (!java.util.HexFormat.of().formatHex(digest.digest()).equals(manifest.get("vectorsSha256").getAsString())) throw new IOException("Vector digest differs");
            // 先按原始身份验证二进制头和摘要，再迁移内存身份；矩阵字节及磁盘包不修改。
            var canonical = EmbeddingSpaces.canonical(descriptor.space());
            return new IconBundle(catalog, LocalIconIndex.owned(IndexDescriptor.of(canonical, catalog), catalog.icons(), matrix), descriptor.space());
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Invalid prebuilt icon bundle", e); }
    }
}
