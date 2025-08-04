package com.nekoyu.Universe.LawsLoader;

import com.nekoyu.Universe.Universe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class LawsManager {
    Yaml yaml = new Yaml();
    Logger logger = LoggerFactory.getLogger(getClass());
    Map<String, Law> laws = new HashMap<>();
    Map<String, LawCFG> lawCFGs = new HashMap<>();

    public void loadLaws() {
        File[] jarFiles = new File("./laws/").listFiles(file -> file.getName().endsWith(".jar"));
        Map<URL, LawCFG> urlToLawCFG = new HashMap<>();

        // 第一阶段：只读 law.yml，不加载类
        for (File file : jarFiles) {
            try (JarFile jarFile = new JarFile(file)) {
                ZipEntry entry = jarFile.getEntry("law.yml"); // 不能加"./"!!!!!
                if (entry == null) {
                    logger.warn("{} 中没有 law.yml，将不会被加载", file.getName());
                    continue;
                }
                try (InputStream inputStream = jarFile.getInputStream(entry)) {
                    LawCFG lawCFG = yaml.loadAs(inputStream, LawCFG.class);
                    if (lawCFG.main == null && lawCFG.name == null) {
                        logger.warn("{} 的 law.yml 没有定义 主类 或 唯一限定名，将不会被加载", file.getName());
                        continue;
                    }
                    URL jarUrl = file.toURI().toURL();
                    lawCFG.url = jarUrl;
                    lawCFGs.put(lawCFG.name, lawCFG);
                    urlToLawCFG.put(jarUrl, lawCFG);
                }
            } catch (Exception e) {
                logger.error("加载 {} 失败", file.getName(), e);
            }
        }

        // 第二阶段：检查依赖可用性
        for (LawCFG lawCFG : lawCFGs.values()) {
            lawCFG.loadAble = true;
            if (lawCFG.dependencies != null) {
                for (String dep : lawCFG.dependencies) {
                    if (!lawCFGs.containsKey(dep)) {
                        lawCFG.loadAble = false;
                        logger.warn("法则 {} 缺失前置法则 {}，无法加载", lawCFG.name, dep);
                    }
                }
            }
        }

        // 第三阶段：分组
        Map<String, List<URL>> registeredMap = new HashMap<>();
        List<List<URL>> dependencyGroups = new ArrayList<>();
        for (LawCFG cfg : lawCFGs.values()) {
            if (!cfg.loaded && cfg.loadAble) {
                List<URL> newGroup = new ArrayList<>();
                groupByDependencies(cfg, newGroup, registeredMap, dependencyGroups);
            }
        }

        // 第四阶段：加载（修改后）
        for (List<URL> group : dependencyGroups) {
            logger.debug("准备加载分组: {}", group);

            // 创建类加载器但不自动关闭
            URLClassLoader classLoader = new URLClassLoader(
                    group.toArray(new URL[0]),
                    getClass().getClassLoader()
            );

            for (URL url : group) {
                LawCFG cfg = urlToLawCFG.get(url);
                if (cfg != null) {
                    try {
                        // 设置上下文类加载器
                        ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
                        Thread.currentThread().setContextClassLoader(classLoader);

                        Class<?> clazz = Class.forName(cfg.main, true, classLoader);
                        Law law = (Law) clazz.getDeclaredConstructor().newInstance();
                        law.ID = cfg.name;
                        laws.put(cfg.name, law);

                        logger.info("成功加载法则: {}", cfg.name);
                        Thread.currentThread().setContextClassLoader(originalLoader);
                    } catch (Exception e) {
                        logger.error("加载法则 {} 失败", cfg.name, e);
                    }
                }
            }
        }
    }

    private void groupByDependencies(LawCFG lawCFG, List<URL> currentList, Map<String, List<URL>> registeredLists, List<List<URL>> resultGroups) {

        // 1. 使用包装器解决 lambda 限制
        class ListWrapper {
            List<URL> list;
            ListWrapper(List<URL> list) {
                this.list = list;
            }
        }
        ListWrapper wrapper = new ListWrapper(currentList);

        // 2. 检查是否已处理
        if (lawCFG.loaded) return;

        // 3. 确保当前列表已注册
        if (!resultGroups.contains(wrapper.list)) {
            resultGroups.add(wrapper.list);
        }

        // 4. 添加当前法则URL
        if (!wrapper.list.contains(lawCFG.url)) {
            wrapper.list.add(lawCFG.url);
        }
        lawCFG.loaded = true;

        // 5. 在注册表中记录当前法则
        registeredLists.put(lawCFG.name, wrapper.list);

        // 6. 处理依赖项
        if (lawCFG.dependencies != null) for (String depName : lawCFG.dependencies) {
            LawCFG depCFG = lawCFGs.get(depName);
            if (depCFG == null) continue;

            // 递归处理未加载的依赖
            if (!depCFG.loaded) {
                groupByDependencies(depCFG, wrapper.list, registeredLists, resultGroups);
            }

            // 检查依赖是否在其它组
            List<URL> depList = registeredLists.get(depName);
            if (depList != null && depList != wrapper.list) {
                // 合并列表
                depList.addAll(wrapper.list);
                resultGroups.remove(wrapper.list);

                // 更新所有指向当前列表的注册项（使用包装器解决 lambda 问题）
                registeredLists.replaceAll((k, v) ->
                        v == wrapper.list ? depList : v
                );

                // 更新包装器指向新列表
                wrapper.list = depList;
            }
        }
    }

    public void prepareLaws() {
        for (Law law : laws.values()) {
            prepareLaw(law);
        }
    }

    private void prepareLaw(Law law) {
        if (law.isPrepared) return;
        if (law.Dependencies != null) {
            List<String> missedDependencies = new ArrayList<>();
            for (String dependency : law.Dependencies) {
                Law dependencyLaw = laws.get(dependency);
                if (dependencyLaw == null) {
                    missedDependencies.add(dependency);
                } else {
                    prepareLaw(dependencyLaw);
                }
            }
            if (!missedDependencies.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("由于缺失前置宇宙法则，");
                for (String buffer : missedDependencies) {
                    sb.append(buffer);
                }
                sb.append("，");
                sb.append(law.ID);
                sb.append(" 未就绪");
                Universe.logger.error(sb.toString());
            }
        }
        law.ableToRun = law.prepare();
        law.isPrepared = true;
    }

    public void enableLaws() {
        for (Law law : laws.values()) {
            enableLaw(law);
        }
    }

    public void enableLaw(Law law) {
        if (law.ableToRun && !law.isRunning) {
            // 如果法则有 前置 属性，就要先启动前置法则
            if (law.Dependencies != null) {
                List<String> missedDependencies = new ArrayList<>();
                for (String dependency : law.Dependencies) {
                    Law dependencyLaw = laws.get(dependency);
                    if (dependencyLaw == null) {
                        missedDependencies.add(dependency);
                    }
                    enableLaw(dependencyLaw);
                }
                if (!missedDependencies.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("由于缺失前置宇宙法则，");
                    for (String buffer : missedDependencies) {
                        sb.append(buffer);
                    }
                    sb.append("，");
                    sb.append(law.ID);
                    sb.append(" 无法运行");
                    Universe.logger.error(sb.toString());
                }
            }
            law.run();
            law.isRunning = true;
        }
    }

    public void stopLaws() {
        for (Law law : laws.values()) {
            stopLaw(law);
        }
    }

    private void stopLaw(Law law) {
        if (law.isRunning) {
            law.stop();
            law.isRunning = false;
        }
    }
}
