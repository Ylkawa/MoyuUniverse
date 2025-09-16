package com.nekoyu.Universe.LawsLoader;

import com.nekoyu.Universe.Universe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class LawsManager {
    private final Yaml yaml = new Yaml();
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Map<String, Law> laws = new HashMap<>();
    private final Map<String, LawCFG> lawCFGs = new HashMap<>();
    private final Map<String, URLClassLoader> lawClassLoaders = new HashMap<>(); // <-- 保存每个 law 的 classloader

    public void loadLaws() {
        File[] jarFiles = Optional.ofNullable(new File("./laws/").listFiles(f -> f.getName().endsWith(".jar")))
                .orElse(new File[0]);
        Map<URL, LawCFG> urlToLawCFG = new HashMap<>();

        // 1) 读取 law.yml（只读配置）
        for (File file : jarFiles) {
            try (JarFile jarFile = new JarFile(file)) {
                ZipEntry entry = jarFile.getEntry("law.yml");
                if (entry == null) {
                    logger.warn("{} 中没有 law.yml，跳过", file.getName());
                    continue;
                }
                try (InputStream in = jarFile.getInputStream(entry)) {
                    LawCFG cfg = yaml.loadAs(in, LawCFG.class);
                    if (cfg == null || cfg.main == null || cfg.name == null) {
                        logger.warn("{} 的 law.yml 缺少 main/name，跳过", file.getName());
                        continue;
                    }
                    URL jarUrl = file.toURI().toURL();
                    cfg.url = jarUrl;
                    lawCFGs.put(cfg.name, cfg);
                    urlToLawCFG.put(jarUrl, cfg);
                }
            } catch (Exception e) {
                logger.error("加载 {} 失败", file.getName(), e);
            }
        }

        // 2) 检查依赖可用性
        for (LawCFG cfg : lawCFGs.values()) {
            cfg.loadAble = cfg.dependencies == null || cfg.dependencies.stream().allMatch(lawCFGs::containsKey);
            if (!cfg.loadAble) {
                logger.warn("法则 {} 缺失依赖 {}", cfg.name, cfg.dependencies);
            }
        }

        // 3) 分组（按依赖合并）
        List<List<URL>> groups = new ArrayList<>();
        Map<String, List<URL>> regMap = new HashMap<>();
        for (LawCFG cfg : lawCFGs.values()) {
            if (!cfg.loaded && cfg.loadAble) {
                List<URL> group = new ArrayList<>();
                groupByDependencies(cfg, group, regMap, groups);
            }
        }

        // 4) 为每个分组创建 URLClassLoader 并加载类 **(不要关闭 classloader)**，
        //    并把 classloader 与组中每个 law 关联起来（以便后续运行线程使用）
        for (List<URL> group : groups) {
            logger.debug("准备加载分组: {}", group);
            URL[] urls = group.toArray(new URL[0]);
            URLClassLoader cl = new URLClassLoader(urls, getClass().getClassLoader()); // <-- 不要放到 try-with-resources

            for (URL url : group) {
                LawCFG cfg = urlToLawCFG.get(url);
                if (cfg == null) continue;
                ClassLoader previousCtx = Thread.currentThread().getContextClassLoader();
                try {
                    // 临时把当前线程的上下文类加载器切到插件的 classloader，
                    // 这样插件初始化期间如果有 Class.forName(...)（不带 classloader 参数）也能找到类。
                    Thread.currentThread().setContextClassLoader(cl);

                    Class<?> clazz = Class.forName(cfg.main, true, cl); // 显式用 cl 加载主类
                    Law law = (Law) clazz.getDeclaredConstructor().newInstance();
                    law.ID = cfg.name;
                    laws.put(cfg.name, law);

                    // 保存该 law 使用的 classloader（用于启动它的线程）
                    lawClassLoaders.put(cfg.name, cl);

                    logger.info("成功加载法则: {}", cfg.name);
                } catch (Exception e) {
                    logger.error("加载法则 {} 失败", cfg.name, e);
                } finally {
                    Thread.currentThread().setContextClassLoader(previousCtx);
                }
            }
        }
    }

    private void groupByDependencies(LawCFG cfg, List<URL> list,
                                     Map<String, List<URL>> reg, List<List<URL>> groups) {
        if (cfg.loaded) return;
        if (!groups.contains(list)) groups.add(list);
        if (!list.contains(cfg.url)) list.add(cfg.url);
        cfg.loaded = true;
        reg.put(cfg.name, list);

        if (cfg.dependencies != null) {
            for (String dep : cfg.dependencies) {
                LawCFG depCfg = lawCFGs.get(dep);
                if (depCfg == null) continue;
                groupByDependencies(depCfg, list, reg, groups);

                List<URL> depList = reg.get(dep);
                if (depList != null && depList != list) {
                    depList.addAll(list);
                    groups.remove(list);
                    // 替换所有引用旧 list 的注册表项
                    List<URL> finalList = list;
                    reg.replaceAll((k, v) -> v == finalList ? depList : v);
                    list = depList;
                }
            }
        }
    }

    // 依赖检查/准备/启动逻辑（与之前简化逻辑类似）
    private boolean checkDependenciesAndPrepare(Law law, boolean preparePhase) {
        if (law.Dependencies == null) return true;
        List<String> missing = new ArrayList<>();
        for (String dep : law.Dependencies) {
            Law depLaw = laws.get(dep);
            if (depLaw == null) {
                missing.add(dep);
            } else if (preparePhase && !depLaw.isPrepared) {
                synchronized (depLaw) {
                    if (!depLaw.isPrepared) prepareLaw(depLaw);
                }
            }
        }
        if (!missing.isEmpty()) {
            Universe.logger.error("由于缺失前置宇宙法则 {}，{} {}", String.join(",", missing),
                    law.ID, preparePhase ? "未就绪" : "无法运行");
            return false;
        }
        return true;
    }

    public void prepareLaws() { laws.values().forEach(this::prepareLaw); }

    private void prepareLaw(Law law) {
        if (law.isPrepared) return;
        if (!checkDependenciesAndPrepare(law, true)) return;
        new Thread(() -> {
            synchronized (law) {
                law.ableToRun = law.prepare();
                law.isPrepared = true;
            }
        }).start();
    }

    public void enableLaws() {
        for (var law : laws.values()) {
            new Thread(() -> {
                logger.info("启动 {} ...", law.ID);
                enableLaw(law);
            }).start();
        }
    }

    public void enableLaw(Law law) {
        while (!law.isPrepared) {
            Thread.yield();
        }
        if (!law.ableToRun) {
            logger.warn("{} 报告未就绪，不会运行", law.ID);
            return;
        }
        if (law.isRunning) return;

        // 启动前确保前置法则先启动
        if (law.Dependencies != null) {
            List<String> missing = new ArrayList<>();
            for (String dep : law.Dependencies) {
                Law depLaw = laws.get(dep);
                if (depLaw == null) missing.add(dep);
                else enableLaw(depLaw);
            }
            if (!missing.isEmpty()) {
                Universe.logger.error("由于缺失前置宇宙法则 {}，{} 无法运行", String.join(",", missing), law.ID);
                return;
            }
        }

        // 启动 law 的线程，并把对应的 classloader 设置为该线程的上下文类加载器
        URLClassLoader cl = lawClassLoaders.get(law.ID);
        Thread t = new Thread(new LawThread(law), "Law-" + law.ID);
        if (cl != null) t.setContextClassLoader(cl); // <-- 关键：保证插件线程的 context loader
        t.start();
    }

    public void stopLaws() { laws.values().forEach(this::stopLaw); }

    private void stopLaw(Law law) {
        if (law.isRunning) {
            law.stop();
            law.isRunning = false;
        }
    }

    // 可选：在程序关闭或卸载插件时调用，关闭所有 classloader（如果你需要释放文件句柄）
    public void closeAllLoaders() {
        // 注意：关闭 classloader 后，相关类将不可再加载。确保先 stopLaws()
        new ArrayList<>(lawClassLoaders.values()).forEach(cl -> {
            try { cl.close(); } catch (Exception e) { logger.warn("关闭 classloader 失败", e); }
        });
        lawClassLoaders.clear();
    }
}
