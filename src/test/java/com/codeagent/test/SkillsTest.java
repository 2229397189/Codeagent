package com.codeagent.test;

import com.codeagent.skills.Skill;
import com.codeagent.skills.SkillRegistry;

/**
 * 技能（Skill）路由测试：断言「返回正确的值」，而非仅不报错。
 * 覆盖 内置技能注册、按触发词路由、无命中返回 null、markdown frontmatter 解析。
 */
public class SkillsTest {

    public static int run() {
        int fails = 0;
        System.out.println("[SkillsTest]");

        SkillRegistry reg = new SkillRegistry();
        fails += check("builtin skills are registered", reg.all().size() >= 3);

        // ---- 路由：按触发词命中数取最高分 ----
        Skill review = reg.route("please review this diff");
        fails += check("route picks code-review for review query",
                review != null && "code-review".equals(review.name));

        Skill explain = reg.route("explain why this works");
        fails += check("route picks explain for explain query",
                explain != null && "explain".equals(explain.name));

        Skill none = reg.route("what is the weather tomorrow");
        fails += check("route returns null when nothing matches", none == null);

        // ---- markdown 技能文件解析（frontmatter）----
        String md = "---\nname: deploy\ndescription: deploy to production\n"
                + "triggers: deploy, rollback\ntools: run_command\n---\nDeploy carefully.\n";
        Skill parsed = SkillRegistry.parseMarkdown(md);

        fails += check("parseMarkdown reads name", parsed != null && "deploy".equals(parsed.name));
        fails += check("parseMarkdown reads triggers", parsed != null
                && parsed.triggers.contains("deploy") && parsed.triggers.contains("rollback"));
        fails += check("parseMarkdown reads tool allowlist",
                parsed != null && parsed.allowedTools.contains("run_command"));
        fails += check("parsed skill matches its own trigger",
                parsed != null && parsed.matchScore("please deploy now") >= 1);

        fails += check("malformed markdown yields null",
                SkillRegistry.parseMarkdown("no frontmatter here") == null);

        // ---- 渲染进 prompt ----
        String rendered = reg.renderForPrompt(review);
        fails += check("renderForPrompt lists available skills", rendered.contains("Available skills"));
        fails += check("renderForPrompt includes active skill instructions",
                rendered.contains("Active skill: code-review"));

        return fails;
    }

    static int check(String name, boolean cond) {
        if (cond) {
            System.out.println("  PASS " + name);
            return 0;
        }
        System.out.println("  FAIL " + name);
        return 1;
    }
}
