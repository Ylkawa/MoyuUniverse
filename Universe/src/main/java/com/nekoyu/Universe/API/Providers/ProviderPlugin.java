package com.nekoyu.Universe.API.Providers;

import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.LawsLoader.LawCFG;
import org.yaml.snakeyaml.Yaml;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** 独立维护工具只准备明确指定的 Provider 插件，复用 law.yml 和正常 prepare 注册流程。 */
public final class ProviderPlugin implements AutoCloseable {
    private final Law law;
    private final URLClassLoader loader;
    private ProviderPlugin(Law law, URLClassLoader loader) { this.law = law; this.loader = loader; }
    public static ProviderPlugin open(Path path) throws IOException {
        URLClassLoader loader = null;
        try {
            LawCFG config;
            try (var jar = new JarFile(path.toFile())) {
                var entry = jar.getJarEntry("law.yml");
                if (entry == null) throw new IOException("Provider plugin is missing law.yml");
                try (var input = jar.getInputStream(entry)) { config = new Yaml().loadAs(input, LawCFG.class); }
            }
            if (config == null || config.getName() == null || config.getMain() == null
                    || (config.getDependencies() != null && !config.getDependencies().isEmpty()))
                throw new IOException("Expected a standalone Provider plugin");
            loader = new URLClassLoader(new java.net.URL[]{path.toUri().toURL()}, ProviderPlugin.class.getClassLoader());
            Law law = (Law) Class.forName(config.getMain(), true, loader).getDeclaredConstructor().newInstance();
            law.ID = config.getName();
            if (!law.prepare()) { law.stop(); throw new IOException("Provider plugin preparation failed"); }
            return new ProviderPlugin(law, loader);
        } catch (Exception e) {
            if (loader != null) loader.close();
            if (e instanceof IOException io) throw io;
            throw new IOException("Cannot prepare Provider plugin", e);
        }
    }
    @Override public void close() throws IOException { try { law.stop(); } finally { loader.close(); } }
}
