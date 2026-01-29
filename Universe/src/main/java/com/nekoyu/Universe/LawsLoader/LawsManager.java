package com.nekoyu.Universe.LawsLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class LawsManager {
    private final Yaml yaml = new Yaml();
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Map<String, Law> laws = new HashMap<>();
    private final Map<String, LawCFG> lawCFGs = new HashMap<>();
    private final Map<String, URLClassLoader> lawClassLoaders = new HashMap<>();

    public void loadLaws() {
        // 读取所有 laws 目录下的 .jar 文件
        File[] jarFiles = new File("./laws/").listFiles((dir, name) -> name.endsWith(".jar"));
        if (jarFiles == null) jarFiles = new File[0];

        // 1) 从每个 JAR 包中读取 law.yml 配置
        for (File file : jarFiles) {
            try (JarFile jar = new JarFile(file)) {
                ZipEntry entry = jar.getEntry("law.yml");
                if (entry == null) {
                    logger.warn("{} 中没有 law.yml，跳过", file.getName());
                    continue;
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    LawCFG cfg = yaml.loadAs(in, LawCFG.class);
                    if (cfg == null || cfg.main == null || cfg.name == null) {
                        logger.warn("{} 的 law.yml 缺少 main/name，跳过", file.getName());
                        continue;
                    }
                    cfg.url = file.toURI().toURL();
                    lawCFGs.put(cfg.name, cfg);
                }
            } catch (Exception e) {
                logger.error("加载 {} 失败", file.getName(), e);
            }
        }

        // 2) 检查依赖完整性
        for (LawCFG cfg : lawCFGs.values()) {
            cfg.loadAble = (cfg.dependencies == null)
                    || cfg.dependencies.stream().allMatch(lawCFGs::containsKey);
            if (!cfg.loadAble) {
                logger.warn("法则 {} 缺失依赖 {}", cfg.name, cfg.dependencies);
            }
        }

        // 3) 加载每个法则及其依赖的类
        for (LawCFG cfg : lawCFGs.values()) {
            if (!cfg.loadAble) continue;
            // 递归收集当前法则及其所有依赖的 JAR URL
            Set<URL> urls = new HashSet<>();
            gatherDependencyURLs(cfg, urls);
            URL[] urlArray = urls.toArray(new URL[0]);
            URLClassLoader cl = new URLClassLoader(urlArray, getClass().getClassLoader());
            try {
                // 切换上下文类加载器以保证 Class.forName 能找到类
                Thread.currentThread().setContextClassLoader(cl);
                Class<?> clazz = Class.forName(cfg.main, true, cl);
                Law law = (Law) clazz.getDeclaredConstructor().newInstance();
                law.ID = cfg.name;
                laws.put(cfg.name, law);
                lawClassLoaders.put(cfg.name, cl);
                logger.info("成功加载法则: {}", cfg.name);
            } catch (Exception e) {
                logger.error("加载法则 {} 失败", cfg.name, e);
            } finally {
                // 恢复原始上下文类加载器（可选）
                Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
            }
        }
    }

    // 递归收集法则及其依赖的 URL
    private void gatherDependencyURLs(LawCFG cfg, Set<URL> set) {
        if (cfg == null || set.contains(cfg.url)) return;
        set.add(cfg.url);
        if (cfg.dependencies != null) {
            for (String dep : cfg.dependencies) {
                gatherDependencyURLs(lawCFGs.get(dep), set);
            }
        }
    }

    // 依赖检查/准备逻辑
    private boolean checkDependenciesAndPrepare(Law law) {
        if (law.Dependencies != null) {
            List<String> missing = new ArrayList<>();
            for (String dep : law.Dependencies) {
                Law depLaw = laws.get(dep);
                if (depLaw == null) {
                    missing.add(dep);
                } else if (!depLaw.isPrepared) {
                    prepareLaw(depLaw);
                    if (!depLaw.isPrepared) return false;
                }
            }
            if (!missing.isEmpty()) {
                logger.error("由于缺失前置宇宙法则 {}，{} {}",
                        String.join(",", missing), law.ID,
                        "未就绪");
                return false;
            }
        }
        return true;
    }

    // 顺序调用各法则的 prepare
    public void prepareLaws() {
        for (Law law : laws.values()) {
            prepareLaw(law);
        }
    }

    private void prepareLaw(Law law) {
        if (law.isPrepared) return;
        if (!checkDependenciesAndPrepare(law)) return;
        law.ableToRun = law.prepare();
        law.isPrepared = true;
    }

    // 同时启动各法则
    public void enableLaws() {
        ExecutorService executor = Executors.newCachedThreadPool();
        for (Law law : laws.values()) {
            executor.submit(() -> enableLaw(law));
        }
        executor.shutdown();
        try {
            executor.awaitTermination(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.warn("宇宙法则加载超时");
            throw new RuntimeException(e);
        }
    }

    public void enableLaw(Law law) {
        if (!law.isPrepared) prepareLaw(law);
        if (!law.ableToRun) {
            logger.warn("{} 报告未就绪，不会运行", law.ID);
            return;
        }
        if (law.Dependencies != null) {
            for (String dep : law.Dependencies) {
                Law depLaw = laws.get(dep);
                if (depLaw == null) {
                    logger.error("由于缺失前置宇宙法则 {}，{} 无法运行", dep, law.ID);
                    return;
                }
                if (!depLaw.isRunning) {
                    enableLaw(depLaw);
                }
            }
        }
        if (law.isRunning) return;
        logger.info("启动 {} ...", law.ID);
        URLClassLoader cl = lawClassLoaders.get(law.ID);
        Thread t = new Thread(new LawThread(law), "Law-" + law.ID);
        if (cl != null) t.setContextClassLoader(cl);
        t.start();
    }

    public void stopLaws() {
        for (Law law : laws.values()) {
            if (law.isRunning) {
                law.stop();
                law.isRunning = false;
            }
        }
    }

    // 关闭所有 ClassLoader 并释放资源
    public void closeAllLoaders() {
        for (URLClassLoader cl : new ArrayList<>(lawClassLoaders.values())) {
            try {
                cl.close();
            } catch (Exception e) {
                logger.warn("关闭 classloader 失败", e);
            }
        }
        lawClassLoaders.clear();
    }
}
