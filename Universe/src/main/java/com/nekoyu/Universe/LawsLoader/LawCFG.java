package com.nekoyu.Universe.LawsLoader;

import java.io.File;
import java.net.URL;
import java.util.List;

public class LawCFG {
    String name;
    String version;
    String main;
    List<String> dependencies;
    URL url;
    boolean loaded = false;
    boolean loadAble = true;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getMain() {
        return main;
    }

    public void setMain(String main) {
        this.main = main;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<String> dependencies) {
        this.dependencies = dependencies;
    }
}
