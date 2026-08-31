package com.nekoyu.Universe.AIChat.Skill;

import com.nekoyu.Universe.AIChat.Topic;
import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class SkillManager {
    private static final Logger logger = LoggerFactory.getLogger(SkillManager.class);
    private static final String SKILL_META_KEY = "skillId";
    private static final String SYSTEM_ROLE = "system";

    private final Topic topic;
    private final Set<String> activeSkills = new LinkedHashSet<>();
    private final Set<String> availableSkills;
    private final Set<String> alwaysSkills;

    public SkillManager(Topic topic, Collection<String> availableSkillIds, Collection<String> alwaysSkillIds) {
        this.topic = topic;
        this.alwaysSkills = new LinkedHashSet<>(alwaysSkillIds != null ? alwaysSkillIds : Collections.emptyList());
        this.availableSkills = new LinkedHashSet<>(availableSkillIds != null ? availableSkillIds : Collections.emptyList());
        this.availableSkills.addAll(this.alwaysSkills);
    }

    public void initAlwaysSkills() {
        for (String skillId : alwaysSkills) {
            if (activateInternal(skillId)) {
                logger.info("始终加载 Skill: {}", skillId);
            }
        }
    }

    public boolean activate(String skillId) {
        if (!availableSkills.contains(skillId) && !availableSkills.isEmpty()) {
            logger.warn("Skill {} 不在可用列表中", skillId);
            return false;
        }
        return activateInternal(skillId);
    }

    private boolean activateInternal(String skillId) {
        if (activeSkills.contains(skillId)) {
            return false;
        }

        Skill skill = SkillRegistry.get(skillId);
        if (skill == null) {
            logger.warn("Skill {} 未注册", skillId);
            return false;
        }

        activeSkills.add(skillId);

        if (skill.systemPrompt != null && !skill.systemPrompt.isEmpty()) {
            MCMessage systemMsg = createSystemMessage(skill.systemPrompt, skillId);
            topic.addSkillMessage(systemMsg);
        }

        logger.info("激活 Skill: {}", skillId);
        return true;
    }

    public boolean deactivate(String skillId) {
        if (!activeSkills.contains(skillId)) {
            return false;
        }

        activeSkills.remove(skillId);
        topic.removeSystemPromptBySkillId(skillId);

        logger.info("去激活 Skill: {}", skillId);
        return true;
    }

    public boolean isActive(String skillId) {
        return activeSkills.contains(skillId);
    }

    public Set<String> getActiveSkills() {
        return Collections.unmodifiableSet(activeSkills);
    }

    public Set<String> getAvailableSkills() {
        return Collections.unmodifiableSet(availableSkills);
    }

    public List<String> getActiveToolNames() {
        List<String> tools = new ArrayList<>();
        for (String skillId : activeSkills) {
            Skill skill = SkillRegistry.get(skillId);
            if (skill != null && skill.toolNames != null) {
                tools.addAll(skill.toolNames);
            }
        }
        return tools;
    }

    public boolean hasOnDemandSkills() {
        for (String skillId : availableSkills) {
            if (!alwaysSkills.contains(skillId) && !activeSkills.contains(skillId)) {
                Skill skill = SkillRegistry.get(skillId);
                if (skill != null && skill.mode == Skill.Mode.ON_DEMAND) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<Skill> getAvailableOnDemandSkills() {
        List<Skill> result = new ArrayList<>();
        for (String skillId : availableSkills) {
            if (!alwaysSkills.contains(skillId)) {
                Skill skill = SkillRegistry.get(skillId);
                if (skill != null && skill.mode == Skill.Mode.ON_DEMAND) {
                    result.add(skill);
                }
            }
        }
        return result;
    }

    private MCMessage createSystemMessage(String content, String skillId) {
        MCMessage msg = new MCMessage();
        msg.putMetainfo("role", SYSTEM_ROLE);
        msg.putMetainfo(SKILL_META_KEY, skillId);
        msg.messageFields.add(new TextField(content));
        return msg;
    }
}
