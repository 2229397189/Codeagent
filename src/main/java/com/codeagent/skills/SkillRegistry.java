package com.codeagent.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 技能注册与路由。内置若干技能，并从 .codeagent/skills/*.md 加载用户自定义技能。
 * 路由：按用户输入命中触发关键词的数量打分，取最高分技能激活。
 */
public class SkillRegistry {
    private final List<Skill> skills = new ArrayList<>();

    public SkillRegistry() {
        // 内置技能：把常用任务专业化，避免单条 system prompt 过长
        register(new Skill("code-review", "审查代码改动、给出风险与改进", "# Skill: code-review\n审查改动时：先读相关文件与 diff，定位边界条件与并发风险，给出可执行的修改建议。")
                .trigger("review", "审查", "cr", "diff").allow("read_file", "grep", "list_files"));
        register(new Skill("explain", "解释代码/概念", "# Skill: explain\n解释时：结合文件路径与上下文，用类比和关键行说明「为什么这样写」。")
                .trigger("explain", "解释", "为什么", "how does").allow("read_file", "grep"));
        register(new Skill("refactor", "重构与清理", "# Skill: refactor\n重构时：保持行为不变，优先最小改动，先跑测试再提交。")
                .trigger("refactor", "重构", "清理", "cleanup").allow("read_file", "grep", "edit_file", "patch"));
    }

    public void register(Skill s) { skills.add(s); }

    public List<Skill> all() { return new ArrayList<>(skills); }

    /** 从目录加载 *.md 技能文件（简单 frontmatter 格式） */
    public void loadFromDir(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            for (Path p : stream.filter(f -> f.toString().endsWith(".md")).toList()) {
                Skill s = parseMarkdown(Files.readString(p, StandardCharsets.UTF_8));
                if (s != null) skills.add(s);
            }
        } catch (IOException ignore) {
            // 加载失败不致命
        }
    }

    /** 按用户输入路由，返回命中分最高的技能（无命中返回 null） */
    public Skill route(String query) {
        Skill best = null;
        int bestScore = 0;
        for (Skill s : skills) {
            int sc = s.matchScore(query);
            if (sc > bestScore) { bestScore = sc; best = s; }
        }
        return best;
    }

    /** 渲染技能目录 + 当前激活技能指令，注入 system prompt */
    public String renderForPrompt(Skill active) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Available skills\n");
        for (Skill s : skills) {
            sb.append("- ").append(s.name).append(": ").append(s.description).append("\n");
        }
        if (active != null) {
            sb.append("\n## Active skill: ").append(active.name).append("\n");
            sb.append(active.instructions).append("\n");
        }
        return sb.toString().trim();
    }

    /** 解析形如 ---\nname: x\ndescription: y\ntriggers: a,b\n---\nbody 的 markdown 技能文件 */
    public static Skill parseMarkdown(String text) {
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].trim().isEmpty()) i++;
        if (i >= lines.length || !lines[i].trim().equals("---")) return null;
        i++;
        String name = "", description = "", triggers = "", tools = "", body = "";
        int end = -1;
        for (int j = i; j < lines.length; j++) {
            if (lines[j].trim().equals("---")) { end = j; break; }
            String line = lines[j];
            int c = line.indexOf(':');
            if (c > 0) {
                String key = line.substring(0, c).trim().toLowerCase();
                String val = line.substring(c + 1).trim();
                switch (key) {
                    case "name": name = val; break;
                    case "description": description = val; break;
                    case "triggers": triggers = val; break;
                    case "tools": tools = val; break;
                    default: break;
                }
            }
        }
        if (end < 0) return null;
        StringBuilder b = new StringBuilder();
        for (int j = end + 1; j < lines.length; j++) b.append(lines[j]).append("\n");
        body = b.toString().trim();
        if (name.isEmpty()) return null;
        Skill s = new Skill(name, description, body.isEmpty() ? description : body, false);
        for (String t : splitCsv(triggers)) s.triggers.add(t);
        for (String t : splitCsv(tools)) s.allowedTools.add(t);
        return s;
    }

    private static Set<String> splitCsv(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split("[,\\s]+")) {
            String t = p.trim().toLowerCase();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
