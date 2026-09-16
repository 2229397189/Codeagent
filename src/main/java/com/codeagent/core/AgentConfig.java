package com.codeagent.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 运行配置。优先级：命令行参数 > codeagent.properties > 默认值。
 * API Key 永远只从环境变量读取，绝不写进代码或仓库。
 */
public class AgentConfig {
    /** 默认 glm-4.6v：用户资源包生效中（赠送 599 万 token，至 2026-12-13）。
     *  另有 glm-4.5-air（1195 万 token，纯文本编码更省）可用 --model 切换。 */
    public String model = "glm-4.6v";
    public String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
    public String apiKey = System.getenv("CODEAGENT_API_KEY");
    public boolean useMock = "true".equalsIgnoreCase(System.getenv("CODEAGENT_MOCK"));

    public int maxSteps = 25;
    public int contextWarnPct = 70;
    public int contextAutoPct = 90;
    /** review-before-write 默认关闭：改文件必须审批 */
    public boolean acceptEdits = false;

    public String workspace = System.getProperty("user.dir");
    public int microCompactRounds = 6;
    /** 单结果超过该 KB 数则离屏，上下文仅留预览 */
    public int largeResultKb = 32;

    // ---- Phase-2 能力开关：默认全部关闭，保证既有行为与断言不受影响 ----
    /** 长期/短期记忆：跨会话事实库 + 会话内工作黑板 */
    public boolean enableMemory = false;
    /** 基础版 RAG：对语料目录做 BM25 索引并注入检索上下文 */
    public boolean enableRag = false;
    public String ragDir = null;
    /** 技能路由：按输入命中触发词激活专业化 prompt */
    public boolean enableSkills = false;
    public String skillsDir = null;
    /** MCP：外部工具 server 启动命令（可重复传入多个） */
    public List<String> mcpCommands = new ArrayList<>();
    /** 多 Agent 工作流：Plan-and-Execute 编排 */
    public boolean enableWorkflow = false;

    public static AgentConfig load(String[] args) {
        AgentConfig c = new AgentConfig();
        c.loadProps();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.equals("--mock")) c.useMock = true;
                else if (a.equals("--accept-edits")) c.acceptEdits = true;
                else if (a.equals("--model") && i + 1 < args.length) c.model = args[++i];
                else if (a.equals("--base-url") && i + 1 < args.length) c.baseUrl = args[++i];
                else if (a.equals("--api-key") && i + 1 < args.length) c.apiKey = args[++i];
                else if (a.equals("--workspace") && i + 1 < args.length) c.workspace = args[++i];
                else if (a.equals("--max-steps") && i + 1 < args.length) c.maxSteps = Integer.parseInt(args[++i]);
                else if (a.equals("--memory")) c.enableMemory = true;
                else if (a.equals("--workflow")) c.enableWorkflow = true;
                /*
                 * --rag / --skills 的目录参数是「可选」的：开启即使用默认目录。
                 * 写成必填会出现 `--skills --rag docs` 这类误吞（把 --rag 当成 skills 目录），
                 * 因此只在下一个参数不是另一个开关时才消费它。
                 */
                else if (a.equals("--rag")) {
                    c.enableRag = true;
                    String v = takeValue(args, i);
                    c.ragDir = (v == null) ? DEFAULT_RAG_DIR : v;
                    if (v != null) i++;
                } else if (a.equals("--skills")) {
                    c.enableSkills = true;
                    String v = takeValue(args, i);
                    c.skillsDir = (v == null) ? DEFAULT_SKILLS_DIR : v;
                    if (v != null) i++;
                }
                else if (a.equals("--mcp") && i + 1 < args.length) c.mcpCommands.add(args[++i]);
            }
        }
        return c;
    }

    /** 相对工作区解析的默认目录 */
    public static final String DEFAULT_RAG_DIR = "docs";
    public static final String DEFAULT_SKILLS_DIR = ".codeagent/skills";

    /** 取下一个参数：不存在或以 -- 开头（是另一个开关）则视为「没给值」 */
    private static String takeValue(String[] args, int i) {
        if (i + 1 >= args.length) return null;
        String v = args[i + 1];
        return v.startsWith("--") ? null : v;
    }

    private void loadProps() {
        File f = new File("codeagent.properties");
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            if (p.getProperty("model") != null) model = p.getProperty("model");
            if (p.getProperty("base.url") != null) baseUrl = p.getProperty("base.url");
            if (p.getProperty("max.steps") != null) maxSteps = Integer.parseInt(p.getProperty("max.steps"));
            if (p.getProperty("context.auto.pct") != null) contextAutoPct = Integer.parseInt(p.getProperty("context.auto.pct"));
            if (p.getProperty("accept.edits") != null) acceptEdits = Boolean.parseBoolean(p.getProperty("accept.edits"));
            if (p.getProperty("workspace") != null) workspace = p.getProperty("workspace");
        } catch (IOException e) {
            // 忽略：配置缺失不影响启动
        }
    }
}
