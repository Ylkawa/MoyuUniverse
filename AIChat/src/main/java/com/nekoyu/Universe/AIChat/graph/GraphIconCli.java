package com.nekoyu.Universe.AIChat.graph;

import com.nekoyu.Universe.API.Providers.ProviderPlugin;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** 独立的资源准备、续建和验证入口；不接受命令行明文密钥。 */
public final class GraphIconCli {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Expected prepare, build, verify or verify-classpath");
        var options = new LinkedHashMap<String, String>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length || options.putIfAbsent(args[i], args[i + 1]) != null)
                throw new IllegalArgumentException("Invalid CLI option");
        }
        switch (args[0]) {
            case "prepare" -> {
                var catalog = new LucideCatalogImporter().importDirectory(Path.of(required(options, "--source")),
                        required(options, "--version"), Path.of(options.getOrDefault("--output", "data/AIChat/graph-icons")));
                System.out.println("Catalog: " + catalog.licensePath().resolveSibling("catalog.json") + "; icons=" + catalog.icons().size());
            }
            case "build" -> {
                var catalog = new LucideCatalogImporter().loadManifest(Path.of(required(options, "--manifest")));
                if (options.containsKey("--endpoint") || options.containsKey("--api-key-env"))
                    throw new IllegalArgumentException("Configure endpoint and credentials in the registered Adapter, then use --provider");
                try (var plugin = options.containsKey("--provider-plugin") ? ProviderPlugin.open(Path.of(options.get("--provider-plugin"))) : null) {
                var provider = new ProviderIconEmbeddingProvider(options.getOrDefault("--provider", "DashScope"),
                        options.getOrDefault("--model", "tongyi-embedding-vision-plus-2026-03-06"),
                        Integer.parseInt(options.getOrDefault("--dimension", "1152")), options.getOrDefault("--space-version", "v1"));
                var bundle = new IconBundleBuilder().build(catalog, provider, Path.of("data/AIChat/graph-embeddings/images"),
                        Path.of(options.getOrDefault("--output", "AIChat/src/main/resources/graph/icons")),
                        Integer.parseInt(options.getOrDefault("--batch-size", "16")));
                report(bundle);
                System.out.println("This build input tokens=" + provider.inputTokens());
                }
            }
            case "verify" -> report(IconBundle.load(IconAssetSource.directory(Path.of(required(options, "--bundle")))));
            case "verify-classpath" -> {
                var loader = GraphIconCli.class.getClassLoader();
                if (loader.getResource(IconBundle.PREFIX + "current.json") == null) {
                    if (options.getOrDefault("--required", "false").equals("true")) throw new IllegalStateException("Missing production icon bundle");
                    System.out.println("No icon bundle: graph renders without icons");
                } else report(IconBundle.load(IconAssetSource.classpath(loader, IconBundle.PREFIX)));
            }
            default -> throw new IllegalArgumentException("Unknown command");
        }
    }
    private static String required(java.util.Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required: " + name);
        return value;
    }
    private static void report(IconBundle bundle) {
        System.out.println("Verified icons=" + bundle.catalog().icons().size() + ", dimension=" + bundle.index().descriptor().space().dimension()
                + ", identity=" + bundle.index().descriptor().fingerprint());
    }
}
