package com.nekoyu.Universe.LawsLoader;

import com.nekoyu.Universe.Universe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LawsManager {
    Yaml yaml = new Yaml();
    Logger logger = LoggerFactory.getLogger(getClass());
    Map<String, Law> laws = new HashMap<>();
    Map<String, URLClassLoader> classLoaders = new HashMap<>();

    public void loadLaws() {
        File[] jarFiles = new File("./laws/").listFiles(file -> file.getName().endsWith(".jar"));
        for (File file : jarFiles) {
            try {
                URL[] url = {file.toURI().toURL()};
                URLClassLoader urlClassLoader = new URLClassLoader(url, getClass().getClassLoader());

                var inputStream = urlClassLoader.getResourceAsStream("law.yml");
                if (inputStream == null) {
                    logger.warn("{} 中没有 law.yml", file.getName());
                    logger.warn("{} 将不会被加载", file.getName());
                    continue;
                }

                var lawCFG = yaml.loadAs(inputStream, LawCFG.class);
                if (lawCFG.main == null && lawCFG.name == null) {
                    logger.warn("{} 的 law.yml 没有定义 主类 或 唯一限定名", file.getName());
                    logger.warn("{} 将不会被加载", file.getName());
                    continue;
                }

                Class<?> clazz = Class.forName(lawCFG.main, true, urlClassLoader);
                if (!Law.class.isAssignableFrom(clazz)) {
                    logger.warn("{}({}) 的主类不是 Law 的子类", file.getName(), lawCFG.name);
                    logger.warn("{} 将不会被加载", file.getName());
                    continue;
                }

                Law law = (Law) clazz.getDeclaredConstructor().newInstance();
                law.ID = lawCFG.name;
                if (lawCFG.dependencies != null) {
                    law.Dependencies = lawCFG.dependencies.toArray(new String[0]);
                } else {
                    law.Dependencies = null;
                }


                laws.put(law.ID, law);
                classLoaders.put(law.ID, urlClassLoader);

            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            } catch (InstantiationException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
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
