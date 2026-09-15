# CodeAgent 项目长期记忆

## 项目定位
零依赖 Java 17 从零实现的终端编码 Agent（Codex-like），目标：① 简历 AI 项目；② 面试可讲透的 Agent 工程实践。
仓库 https://github.com/2229397189/Codeagent.git ｜ 目录 D:\code\CodeAgent

## 架构（5 模块 + CLI）
- `core/` 主循环 AgentLoop（显式终止枚举 FINAL/FAILED/AWAITING_USER/CONTROLLED_STOP/MAX_STEPS）、ChatModelAdapter、Mock/OpenAI 模型、自研 Json
- `tools/` 统一协议 Tool/ToolRegistry/ToolSpec/ToolResult；6 工具 read/grep/list/edit/patch/run_command；ToolSupport 路径沙箱、DiffUtil unified diff
- `context/` ContextBudget（provider usage 记账 70 warn/90 auto/100 hard）、MicroCompact、AutoCompact、ToolResultStorage、DefaultContextGovernance
- `permission/` DefaultPermissionManager 三态 ALLOW/DENY/ASK + 审批持久化 `.codeagent/permissions.json`；权限门在 ToolRegistry.execute
- `session/` SessionStore JSONL append-only；Session resume/rename/fork
- `observability/` TraceRecorder 审计追踪（状态/步数/token 用量）
- `cli/` CodeAgentCli REPL（/tools /status /compact /approve /session /trace）+ SystemPrompt 边界注入

## 构建与测试（本机 Git Bash shim 缺 coreutils，勿用 cd/rm/find/grep/tail）
```
javac -encoding UTF-8 -d D:/code/CodeAgent/out \
  D:/code/CodeAgent/src/main/java/com/codeagent/**/*.java \
  D:/code/CodeAgent/src/test/java/com/codeagent/**/*.java
java -cp D:/code/CodeAgent/out com.codeagent.test.AllTests
```
- 测试铁律（用户明确要求）：**断言返回值是预期的正确值**，不是「返回 200 不报错」就算过。
- 当前 101 项断言全绿（7 个测试类）。

## 已知边界（简历/面试不得越线）
未做：RAG/向量检索、多 Agent 与规划模式、离线评测集、Redis/MySQL。
已有但别吹成完整方案：TraceRecorder（基础审计 + token 归因，非 langfuse 级 tracing/eval）；InjectionGuard（模式匹配式注入检测，覆盖不了语义改写/编码混淆）。
review-before-write 当前是「未审批则拦截、审批后落盘并返回 diff」，**尚未**做到「先展示 diff 再批准」。

## 环境事实
- GLM key 账号余额不足（HTTP 429），真实模型调用跑不通；离线 `--mock` 全通。
- GitHub push 间歇性 `CONNECT tunnel failed 502`，需重试；失败先本地 commit 勿丢。
- **GitHub PAT 曾在对话中明文暴露，建议吊销重生成**。
