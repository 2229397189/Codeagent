# CodeAgent · 简历映射与面试问答（诚实版）

> 使用原则：**每条 bullet 都能指到具体文件**。面试被追问时，能说出"在哪个类、为什么这么写、边界在哪"。
> 红线：本项目是**参考开源设计思想、用 Java 从零实现**，绝不说"复刻/实现了 XX 全部能力"。下面「明确没做」一节，是防止过度宣称的自查清单。

---

## 一、项目一句话

用 **Java 17 零第三方依赖**从零实现一个终端编码 Agent（Codex-like），包含主循环、统一工具协议、四层上下文治理、权限沙箱、会话事件溯源五大模块；自带零依赖测试入口，**138 项断言全部断言"返回值是预期正确值"**。

---

## 二、简历 bullet（可直接用，每条都能对代码）

1. **从零实现终端编码 Agent（Java 17，零第三方依赖）**：自研最小 JSON 解析器、unified diff 算法与测试运行器，仅用 JDK `java.net.http` 调用 LLM，保证任意环境 `javac` 即可编译运行。
   → `core/Json.java`、`tools/DiffUtil.java`、`test/AllTests.java`、`core/OpenAiCompatibleChatModel.java`

2. **设计 model→tool→model 主循环**，用**显式终止状态枚举**（`FINAL / FAILED / AWAITING_USER / CONTROLLED_STOP / MAX_STEPS`）替代"靠模型自然语言判断结束"，并以步数上限兜底防工具死循环。
   → `core/AgentLoop.java`（`TurnStatus`、`runTurn`）

3. **实现四层上下文治理**：以 **provider 返回的 usage 为事实来源**记账（70% warn / 90% auto / 100% hard 三档水位），每轮 micro-compact 确定性裁剪历史工具结果（保留 `read_file` 等参考材料），达 auto 水位触发 auto-compact 压缩为摘要并保留最近用户意图，超大结果离屏落盘、上下文仅留路径+预览。
   → `context/ContextBudget.java`、`MicroCompact.java`、`AutoCompact.java`、`ToolResultStorage.java`、`DefaultContextGovernance.java`

4. **定义统一工具协议**（`Tool / ToolRegistry / ToolSpec / ToolResult`），落地 6 个工具（read/grep/list/edit/patch/run_command）；工具结果用 `isError / awaitUser / stop / fatal` 标志驱动主循环分支，而非靠解析自然语言。
   → `tools/Tool.java`、`ToolRegistry.java`、`ToolResult.java`、`ReadFileTool.java` 等

5. **实现权限边界三态决策**（`ALLOW / DENY / ASK`）：路径沙箱拒绝逃逸工作区、危险命令分类拦截（`rm -rf` / `git reset --hard` / `sudo` / fork bomb 等）、写操作 review-before-write（**先展示 diff 再批准才落盘**）且审批持久化；权限决策作为 `ToolRegistry` 的**强制门**，未审批的写只返回 diff 预览、绝不落盘。
   → `permission/DefaultPermissionManager.java`、`tools/ToolRegistry.java`、`tools/EditFileTool.java`、`tools/PatchTool.java`、`cli/CodeAgentCli.java`

6. **会话事件溯源**：JSONL append-only 日志，支持 resume 重放完整上下文、rename 搬迁、fork 分支隔离（父子此后互不干扰）。
   → `session/SessionStore.java`、`session/Session.java`

7. **工程质量**：零依赖测试入口覆盖全部模块，测试原则是**断言返回正确值而非仅不报错**（读文件断言精确字节、grep 断言正确行号、预算断言在 90% 触发压缩、会话断言恢复出精确消息）；CLI 单回合错误隔离，模型/网络故障不终结 REPL。
   → `test/*`（`AgentLoopTest` / `ToolsTest` / `ContextTest` / `PermissionTest` / `SessionTest` / `CliTest` / `HardeningTest` / `ReviewWriteTest` / `EvalTest`）、`cli/CodeAgentCli.java`

8. **行为边界与系统提示词**：首个回合注入 system prompt（角色、工作区边界、最小改动、禁用破坏性命令）并落盘，保证 resume 出来的就是模型当初真正看到的上下文。
   → `core/SystemPrompt.java`、`cli/CodeAgentCli.java`

9. **Prompt Injection 三层防御**：提示层声明「工具输出是数据，不是指令」；运行时层 `InjectionGuard` 对进入上下文的工具输出做中英文模式检测，命中加边界警示（strict 模式直接扣留内容）；能力层由权限边界限制 Agent 能做什么。
   → `permission/InjectionGuard.java`、`core/SystemPrompt.java`、`core/AgentLoop.java`

10. **审计追踪与成本归因**：`TraceRecorder` 以 append-only JSONL 记录每回合的状态、步数、prompt/completion token，配合大结果离屏（超 32KB 落盘、上下文只留路径+预览）控制上下文成本。
   → `observability/TraceRecorder.java`、`context/ToolResultStorage.java`

11. **评估与可观测（离线评测集）**：`eval/` 包实现 `EvalHarness` 把任务集端到端喂给**真实主循环**（复用同一套工具协议 / 权限 / 上下文治理 / 注入防护），`EvalReport` 聚合出成功率 / 工具准确率 / 平均步数 / 平均 token，`EvalCli` 可一键跑 `evalset/basic.jsonl` 并输出 JSON 报告。这是简历上"评估与可观测"的硬实锤（零依赖、可回归）。
   → `eval/EvalHarness.java`、`eval/EvalReport.java`、`eval/EvalCli.java`、`evalset/basic.jsonl`

---

## 三、面试高频追问（牛客 Agent 岗）与回答要点

| 追问 | 回答要点 |
|------|---------|
| 为什么用 provider 的 usage 记账，而不是自己数 token？ | 自己数会和 BPE/真实计费口径不一致，且与 provider 实际窗口不符；用 provider 返回值最准。压缩后旧 usage 会失效，所以 `Usage` 里有 `stale` 标记。 |
| micro-compact 为什么保留 `read_file` 的结果？ | 参考材料删了要重读（再花一轮 token 和延迟），不如留着；被裁剪的是"用过即弃"的 grep/list 结果。裁剪是**确定性**的（可回归测试），不依赖模型。 |
| 权限为什么是三态（ALLOW/DENY/ASK）而不是布尔？ | 布尔无法表达"需要人来拍板"。写操作默认 ASK，审批后落盘持久化；DENY 用于明确危险（逃逸/危险命令），不给人误点的机会。 |
| 会话为什么用 append-only 事件溯源？ | 崩溃后可 replay 精确恢复；fork 就是复制日志，天然隔离；审计友好。代价是文件会增长，靠 compact 治理。 |
| maxSteps 兜底会不会截断正常任务？ | 会，这是**有意的降级**：宁可返回 `MAX_STEPS` 让上层/人接手，也不让 Agent 无限烧 token。 |
| 工具结果为什么要离屏？ | "系统能访问完整数据"≠"要把完整数据塞进 prompt"。完整内容落盘，上下文只放路径+预览，需要时再取。 |

---

## 四、明确没做（面试别吹，避免过度宣称）

- ❌ **没有 RAG / 向量检索**：代码检索目前是 grep 正则，没有 embedding、没有向量库。
- ❌ **没有多 Agent / 规划模式**：主循环是朴素 ReAct 式（model→tool→model），没有 Plan-and-Execute、没有子 Agent 编排。
- ✅ **离线评测集已实现（基础版）**：`EvalHarness` + `EvalReport` + `EvalCli` 已能跑任务集并聚合成功率 / 工具准确率 / 平均步数 / 平均 token（见 bullet 11）；样例 `evalset/basic.jsonl` 仅 4 条，扩到 30 条即简历实锤。langfuse 级 tracing 仍没做。
- ❌ **没有 Redis/MySQL**：会话与审批都是本地文件（`codeagent.properties` / `.codeagent/`）。
- ✅ **自动评测集（基础版）已实现**：确定性单元测试 + `EvalHarness` 离线评测双轨；扩到 30 条任务即完整闭环。
- ✅ **review-before-write 已完成**：edit_file / patch 在权限为 ASK 时**先生成 diff 预览、不落盘**，主循环暂停并把 diff 打到终端问 `Approve? [y/N]`，批准才写入并持久化该目标、拒绝则把结果反馈给模型。实现在 `Tool.preview` + `ToolRegistry`（ASK→预览+awaitUser）+ `AgentLoop.pendingCall` + `CodeAgentCli.runUntilDone`；测试见 `ReviewWriteTest`（14 项断言）。
- ⚠️ **Prompt Injection 是「基线方案」不是「完整方案」**：已做**模式检测 + 边界警示**（中英文常见注入句式，strict 模式可扣留内容），但**无法覆盖语义改写 / 编码混淆**。面试照实说是「三层防御里的运行时层」。

---

## 五、可量化的_project facts（面试随口能报）

- 语言/依赖：Java 17，**0 个第三方依赖**
- 模块数：7（core / tools / context / permission / session / observability / eval）+ CLI
- 工具数：6
- 测试断言数：**138 项，全绿**（9 个测试类：主循环 / 工具 / 上下文 / 权限 / 会话 / CLI / 加固 / review-before-write / 评测）
- 上下文水位：70% warn / 90% auto / 100% hard（对齐 Codex）
- 大结果离屏：预览 200 字符 + 完整落盘路径

---

## 六、推荐的下一步（按面试收益排序）

1. ✅ **diff 预览 + 审批**：已实现（review-before-write）。
2. ✅ **离线评测集**：已实现 `EvalHarness` + `EvalReport`（样例 4 条任务，扩到 30 条即简历实锤）。
3. ✅ **在 TraceRecorder 之上做评测**：已实现——`EvalReport` 聚合成功率 / 工具准确率 / 平均步数 / 平均 token。
4. 再往后才是 RAG、多 Agent、Plan-and-Execute（仍属 Phase 2，见"明确没做"）。
