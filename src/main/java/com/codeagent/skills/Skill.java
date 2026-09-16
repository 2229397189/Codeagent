package com.codeagent.skills;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一个「技能」：可被路由激活的领域能力包。
 * 对应 Agent 工程里的「技能/子代理专业化」——把一类任务的提示词与工具白名单固化下来，
 * 由 SkillRegistry 按用户输入路由，避免把所有指令塞进一个超长 system prompt。
 */
public class Skill {
    public final String name;
    public final String description;
    /** 触发关键词（出现在用户输入里即路由到该技能） */
    public final Set<String> triggers = new LinkedHashSet<>();
    /** 注入模型的具体指令（prompt 片段） */
    public final String instructions;
    /** 该技能允许使用的工具白名单；空表示不限制 */
    public final Set<String> allowedTools = new LinkedHashSet<>();
    public final boolean builtin;

    public Skill(String name, String description, String instructions) {
        this(name, description, instructions, true);
    }

    public Skill(String name, String description, String instructions, boolean builtin) {
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        this.builtin = builtin;
    }

    public Skill trigger(String... keys) { for (String k : keys) triggers.add(k.toLowerCase()); return this; }
    public Skill allow(String... tools) { for (String t : tools) allowedTools.add(t); return this; }

    /** 判断该技能是否被 query 触发（关键词命中数） */
    public int matchScore(String query) {
        if (query == null || triggers.isEmpty()) return 0;
        String q = query.toLowerCase();
        int hits = 0;
        for (String t : triggers) if (q.contains(t)) hits++;
        return hits;
    }
}
