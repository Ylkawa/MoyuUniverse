package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.AIChat.Event.RequestEvent;
import com.nekoyu.Universe.AIChat.Skill.Skill;
import com.nekoyu.Universe.AIChat.Skill.SkillRegistry;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public abstract class AIChatPlugin {
    public String id;
    public final Logger logger =  LoggerFactory.getLogger(this.getClass());
    AIChat aiChat;
    public AIChatPlugin(AIChat aiChat) {
        this.aiChat = aiChat;
    } // 留一个空的构造函数备用

    public abstract void onEnable();
    public abstract void onDisable();

    public void onRequest(RequestEvent event) {}

    public File getConfigDir() {
        File file = new File("./config/AIChat/Plugins/" + id + "/");
        if (!file.isDirectory()) file.mkdirs();
        return file;
    }

    public void registerFunction(String toolName, LLMFunction tool) {
        AIChat.registerFunction(toolName, tool);
    }

    public void registerFunction(String[] toolName, LLMFunction tool) {
        for (String name : toolName) {
            registerFunction(name, tool);
        }
    }

    public void registerSkill(Skill skill) {
        SkillRegistry.register(skill);
    }
}
