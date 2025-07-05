package com.nekoyu.LawsLoader;

import com.nekoyu.Universe;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;

public class LawsManager {
    private final Map<String, Law> laws = new HashMap<>();
    private File loc;

    public LawsManager(File dir) {
        this.loc = dir;
    }

    public void loadLaws() {
        if (!loc.exists() || !loc.isDirectory()) {
            System.out.println("宇宙法则目录不存在！");
            return;
        }

        File[] jarFiles = loc.listFiles(file -> file.getName().endsWith(".jar"));
        if (jarFiles == null) return;

        for (File jarFile : jarFiles) {
            try {
                URL[] urls = {jarFile.toURI().toURL()};
                URLClassLoader classLoader = new URLClassLoader(urls, getClass().getClassLoader());

                // 读取 law.yml
                var inputStream = classLoader.getResourceAsStream("law.yml");
                if (inputStream == null) {
                    System.out.println("未找到 law.yml: " + jarFile.getName());
                    continue;
                }

                var properties = new java.util.Properties();
                properties.load(inputStream);
                String mainClass = properties.getProperty("main");
                String lawName = properties.getProperty("name");

                if (mainClass == null || lawName == null) {
                    System.out.println("law.yml 中缺少必要的 main 或 name 属性: " + jarFile.getName());
                    continue;
                }

                // 通过反射加载主类
                Class<?> clazz = Class.forName(mainClass, true, classLoader);

                // 检查是否为 Law 子类
                if (!Law.class.isAssignableFrom(clazz)) {
                    System.out.println("类 " + mainClass + " 不是 Law 的子类: " + jarFile.getName());
                    continue;
                }

                // 确保调用无参构造函数
                Law law = (Law) clazz.getDeclaredConstructor().newInstance();
                law.prepare();

                laws.put(lawName, law);

                Universe.logger.info("成功加载宇宙法则: " + lawName);

            } catch (InstantiationException e) {
                Universe.logger.warn("无法实例化类，确保它有无参构造函数: " + e.getMessage());
            } catch (ClassNotFoundException e) {
                Universe.logger.warn("无法找到主类 " + e.getMessage());
            } catch (Exception e) {
                Universe.logger.warn("加载宇宙法则失败: " + jarFile.getName());
                e.printStackTrace();
            }
        }
    }

    public void enableLaws() {
        laws.values().forEach(Law::run);
    }

    public void disableLaws() {
        laws.values().forEach(Law::stop);
    }

}
