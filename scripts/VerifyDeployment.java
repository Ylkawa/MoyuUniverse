import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Link entry points with the runtime loader hierarchy, without constructors or prepare/onEnable. */
public class VerifyDeployment {
    record Law(Path jar, String main, List<String> dependencies) {}
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Map<String, Law> laws = new LinkedHashMap<>();
        for (Path file : jars(root.resolve("laws"))) {
            thin(file);
            try (JarFile jar = new JarFile(file.toFile()); InputStream in = jar.getInputStream(jar.getJarEntry("law.yml"))) {
                Map<String, Object> yaml = new Yaml().load(in);
                List<String> deps = new ArrayList<>();
                if (yaml.get("dependencies") instanceof List<?> values) values.forEach(v -> deps.add(v.toString()));
                String name = yaml.get("name").toString();
                if (laws.put(name, new Law(file, yaml.get("main").toString(), deps)) != null) throw new IllegalStateException("Duplicate law " + name);
            }
        }
        thin(root.resolve("Universe.jar"));
        link("com.nekoyu.Universe.Universe", ClassLoader.getSystemClassLoader());
        int plugins = 0;
        for (var entry : laws.entrySet()) {
            Set<URL> urls = new LinkedHashSet<>();
            collect(entry.getKey(), laws, urls);
            try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getSystemClassLoader())) {
                link(entry.getValue().main(), loader);
                if (entry.getKey().equals("AIChat")) {
                    List<Path> pluginJars = jars(root.resolve("data/AIChat/Plugins"));
                    URL[] pluginUrls = new URL[pluginJars.size()];
                    for (int i = 0; i < pluginJars.size(); i++) { thin(pluginJars.get(i)); pluginUrls[i] = pluginJars.get(i).toUri().toURL(); }
                    try (URLClassLoader pluginLoader = new URLClassLoader(pluginUrls, loader)) {
                        for (Path file : pluginJars) {
                            try (JarFile jar = new JarFile(file.toFile()); InputStream in = jar.getInputStream(jar.getJarEntry("plug.properties"))) {
                                Properties p = new Properties(); p.load(in);
                                Class<?> type = link(p.getProperty("Main"), pluginLoader);
                                if (!Class.forName("com.nekoyu.Universe.AIChat.AIChatPlugin", false, loader).isAssignableFrom(type)) throw new IllegalStateException("Plugin class identity mismatch");
                                plugins++;
                            }
                        }
                    }
                }
            }
        }
        for (String name : List.of("org.postgresql.Driver", "com.zaxxer.hikari.HikariConfig", "org.openqa.selenium.WebDriver", "jakarta.mail.Session", "org.jsoup.Jsoup")) {
            link(name, ClassLoader.getSystemClassLoader());
        }
        // Fail on duplicate Maven coordinates, including multiple versions of a library.
        Map<String, String> coordinates = new HashMap<>();
        for (Path file : jars(root.resolve("lib"))) {
            try (JarFile jar = new JarFile(file.toFile())) {
                if (jar.getJarEntry("law.yml") != null || jar.getJarEntry("plug.properties") != null) throw new IllegalStateException("Business module duplicated in lib: " + file);
                for (JarEntry e : Collections.list(jar.entries())) {
                    if (e.getName().startsWith("META-INF/maven/") && e.getName().endsWith("/pom.properties")) {
                        Properties p = new Properties(); try (InputStream in = jar.getInputStream(e)) { p.load(in); }
                        // Only the library's own metadata; shaded jars may carry metadata for embedded components.
                        if (!file.getFileName().toString().equals(p.getProperty("artifactId") + "-" + p.getProperty("version") + ".jar")) continue;
                        String key = p.getProperty("groupId") + ":" + p.getProperty("artifactId");
                        if (coordinates.putIfAbsent(key, file.toString()) != null) throw new IllegalStateException("Duplicate runtime library: " + key);
                    }
                }
            }
        }
        System.out.println("Verified thin main + " + laws.size() + " law entry points + " + plugins + " plugin entry points; shared runtime libraries linked; no lifecycle/network started");
    }
    static List<Path> jars(Path path) throws IOException {
        try (var stream = Files.list(path)) { return stream.filter(p -> p.toString().endsWith(".jar")).sorted().toList(); }
    }
    static void collect(String name, Map<String, Law> laws, Set<URL> urls) throws Exception {
        Law law = laws.get(name);
        if (law == null) throw new IllegalStateException("Missing law dependency " + name);
        if (!urls.add(law.jar().toUri().toURL())) return;
        for (String dep : law.dependencies()) collect(dep, laws, urls);
    }
    static Class<?> link(String name, ClassLoader loader) throws Exception {
        Class<?> type = Class.forName(name, false, loader);
        type.getDeclaredConstructors(); type.getDeclaredMethods(); type.getDeclaredFields();
        return type;
    }
    static void thin(Path file) throws IOException {
        try (JarFile jar = new JarFile(file.toFile())) {
            for (JarEntry e : Collections.list(jar.entries())) {
                if (e.getName().endsWith(".class") && !e.getName().startsWith("com/nekoyu/") && !e.getName().equals("module-info.class")) {
                    throw new IllegalStateException("Embedded dependency class in business jar: " + file + " " + e.getName());
                }
            }
        }
    }
}
