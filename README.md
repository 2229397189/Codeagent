# CodeAgent · 本地 AI 编码 Agent（Java 从零实现）

> 定位：跑在终端里的 Codex-like 编码 Agent。参考 Codex / learn-claude-code / MiniCode / Aider / Cline 等开源项目的**设计思想**，用 Java 17 从零实现。
> 用途：① 作为简历 AI 项目；② 面试可讲透的 Agent 工程实践（主循环 / 工具协议 / 上下文治理 / 权限边界 / 会话溯源）。
> 诚信红线：简历与答辩一律写「参考 XX 设计思想，Java 从零实现」，**绝不**写「复刻/实现了 XX 全部能力」。

## 技术决策（MVP）

| 层 | 选型 | 理由 |
|----|------|------|
| 语言 | **Java 17** | 与字节 Java 后端岗同栈；"用上岗同款栈手搓 Agent" 是干净的简历故事 |
| 构建 | **零第三方依赖** | 仅用 JDK（`java.net.http` 调 LLM、自研最小 JSON/diff/测试器），保证任何环境可 `javac` 编译 + 跑出**正确值**的测试 |
| LLM | **OpenAI 兼容 ChatModel** | 默认接 Zhipu GLM（OpenAI 兼容端点）；`base-url` 一键切 DeepSeek / 通义 / 本地 Ollama。自带 `MockChatModel` 供离线测试 |
| CLI | 自研极简参数解析 | 终端优先；`/tools` `/status` `/compact` 等命令 |
| Diff | 自研行级 unified diff | review-before-write 生成 diff，审批后再落盘 |
| 持久化 | 本地文件系统 | JSONL 会话日志 + 大结果落盘；Redis/MySQL 标为 Phase 2，不写进简历 |

> 注：原方案锁定 Spring Boot 3.3 + Spring AI 1.0 + Picocli；因当前构建环境无 Maven、且要求"测试必须返回正确值"，改为零依赖纯 Java 实现，架构完全对齐原方案。如需要 Spring AI 版可另开分支。

## 五大模块（目录对齐原方案）

```
src/main/java/com/codeagent/
├── core/      主循环 AgentLoop · ChatModelAdapter · Mock/OpenAI 模型 · 领域类型 · 配置
├── tools/     统一工具协议 ToolRegistry · Read/Grep/List/Edit/Patch/RunCommand
├── context/   上下文治理 ContextGovernance · Budget · Micro/Auto-Compact · 大结果离屏
├── permission/ 权限 PermissionManager · 沙箱 · 危险命令拦截 · review-before-write · 持久化
├── session/   会话溯源 SessionStore(JSONL) · resume/fork
└── cli/       CodeAgentCli 入口 + REPL
```

## 构建与测试

```bash
build.cmd          # Windows：编译全部源码并跑测试（断言"返回正确值"）
# 或 Linux/Mac：
bash build.sh
```

测试原则：**不只看是否报错，必须断言返回值是预期的正确值**（读文件返回精确字节、grep 返回正确行号、越权路径被拒、预算到 90% 触发压缩、会话恢复还原精确消息等）。

当前 **80 项断言全部通过，0 失败**（`AllTests` 累加 6 个测试类：主循环 / 工具 / 上下文 / 权限 / 会话 / CLI）。

## 运行

```bash
# 真实模型（设置 key，不写进代码/仓库）
set CODEAGENT_API_KEY=你的key
java -cp out com.codeagent.cli.CodeAgentCli --model glm-4.6 --base-url https://open.bigmodel.cn/api/paas/v4

# 离线 mock（无需网络/key，验证主循环与工具协议）
java -cp out com.codeagent.cli.CodeAgentCli --mock
```

### REPL 命令

| 命令 | 作用 |
|------|------|
| `/tools` | 列出已注册工具及其描述 |
| `/status` | 模型 / 工作区 / 会话事件数 / 上下文水位与相位 / 工具数 |
| `/compact` | 预览 auto-compaction 效果（**不改动** append-only 日志） |
| `/approve <tool> <path>` | 审批某个写目标，并持久化到 `.codeagent/permissions.json` |
| `/session` | 当前会话文件与事件数 |
| `/help` `/exit` | 帮助 / 退出 |

> 工程细节：单回合的模型/网络异常会被隔离，返回 `error: ...` 后 REPL 继续可用（见 `CliTest`）。

## 简历与面试

见 `docs/RESUME.md`：每条 bullet 都对到具体代码文件，并**明确列出本项目没做的部分**（RAG / 多 Agent / 评估平台 / Prompt Injection 清洗等），防止面试过度宣称。

## 调研来源（设计思想借鉴，非代码复制）

- GitHub Top 编码 Agent：OpenHands / Aider / Cline / Continue / Roo Code / SWE-agent / Open Interpreter / Goose / OpenCode / Codex CLI
- 牛客 Agent 岗面试高频：上下文窗口治理、工具调用协议、权限边界与 Prompt Injection、规划模式选型、记忆分层、可观测与评估、工程兜底
