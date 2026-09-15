package com.codeagent.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * 运行配置。优先级：命令行参数 > codeagent.properties > 默认值。
 * API Key 永远只从环境变量读取，绝不写进代码或仓库。
 */
public class AgentConfig {
    public String model = "glm-4.6";
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
            }
        }
        return c;
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
