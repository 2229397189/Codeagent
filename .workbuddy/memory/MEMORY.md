# CodeAgent 项目长期记忆

## 项目定位
零依赖 Java 17 从零实现的终端编码 Agent（Codex-like），目标：① 简历 AI 项目；② 面试可讲透的 Agent 工程实践。
仓库 https://github.com/2229397189/Codeagent.git ｜ 目录 D:\code\CodeAgent

## 架构（13 个包 + CLI）
- `core/` 主循环 AgentLoop（显式终止枚举 FINAL/FAILED/AWAITING_USER/CONTROLLED_STOP/MAX_STEPS）、ChatModelAdapter、Mock/OpenAI 模型、自研 Json、AgentConfig
- `tools/` 统一协议 Tool/ToolRegistry/ToolSpec/ToolResult；6 工具 read/grep/list/edit/patch/run_command；ToolSupport 路径沙箱、DiffUtil unified diff
- `context/` ContextBudget（provider usage 记账 70 warn/90 auto/100 hard）、MicroCompact、AutoCompact、ToolResultStorage、DefaultContextGovernance
- `permission/` DefaultPermissionManager 三态 ALLOW/DENY/ASK + 审批持久化 `.codeagent/permissions.json`；权限门在 ToolRegistry.execute；InjectionGuard
- `session/` SessionStore JSONL append-only；Session resume/rename/fork
- `observability/` TraceRecorder 审计追踪（状态/步数/token 用量）
- `eval/` EvalHarness/EvalTask/EvalReport/EvalCli 离线评测集（复用主循环跑端到端，聚合成功率/工具准确率/平均步数/平均 token）
- `retrieval/` 检索底座：Tokenizer（中日英混合：ASCII 词 + CJK unigram + bigram）、InvertedIndex、Bm25 —— **memory 与 rag 共用**
- `memory/` ShortTermMemory（会话内有界黑板，价值=重要性×新鲜度淘汰）+ LongTermMemory（跨会话 JSONL，召回=BM25×重要性×时效性衰减）
- `skills/` Skill/SkillRegistry 触发词打分路由 + `.codeagent/skills/*.md` frontmatter 热加载
- `rag/` Document/Chunk/Chunker/CorpusIndexer/**Reranker**/RagProvider（切分→索引→BM25 召回→**lexical 重排**→注入，基础版）。Reranker 特征=查询词覆盖/标题命中（markdown `#`）/精确短语；默认关闭（`setReranker(null)`）→ 开启后零回归
- `mcp/` JsonRpcClient（零依赖 JSON-RPC 2.0 over stdio，**独立 daemon 读线程防 stdio 双向死锁**）/McpClient/McpTool/McpLauncher/EmbeddedMcpServer（测试桩）
- `workflow/` Plan + Coordinator（Plan-and-Execute：planner/executor/synthesizer 各跑独立 AgentLoop，复用同一权限门）。**M13：单步失败重试 1 次 + 一次重规划**（`executeStep` 重试；失败步标 `[FAILED]`；`maxReplans` 硬截断，6 参构造器默认 1）
- `cli/` CodeAgentCli REPL（/tools /status /compact /approve /session /trace /memory /forget）+ SystemPrompt 边界注入
- Phase-2 开关默认全关：`--memory --rag [dir] --skills [dir] --workflow --mcp <cmd>`（`--rag/--skills` 目录参数**可选**，避免互吞）

## 构建与测试（本机 Git Bash shim 缺 coreutils，勿用 cd/rm/find/grep/tail）
```
javac -encoding UTF-8 -d D:/code/CodeAgent/out \
  D:/code/CodeAgent/src/main/java/com/codeagent/**/*.java \
  D:/code/CodeAgent/src/test/java/com/codeagent/**/*.java
java -cp D:/code/CodeAgent/out com.codeagent.test.AllTests
```
- 测试铁律（用户明确要求）：**断言返回值是预期的正确值**，不是「返回 200 不报错」就算过。
- 当前 **249 项断言全绿（15 个测试类）**：主循环/工具/上下文/权限/会话/CLI/加固/review-before-write/评测/检索/记忆/技能/RAG/MCP/工作流。M13 净增 34 条（WorkflowTest 重试/重规划 4、EvalTest 真实评测集 13、RagTest rerank 9、McpTest 真子进程 5，其余为断言细化）。

## 已知边界（简历/面试不得越线）
- **已实现但只是基础版**（必须标注）：RAG（lexical BM25 + **lexical 特征重排**，无 embedding/向量库；rerank 非 cross-encoder、无语义）；多 Agent（串行 Plan-and-Execute，**含单步重试 + 一次重规划**，无并行/Reflection）；MCP（**只做客户端**，无 server 端、无 resources/prompts）。
- **没做**：Redis/MySQL/MQ（零中间件是刻意设计）；langfuse 级 tracing/eval；完整语义 RAG。
- 已有但别吹成完整方案：TraceRecorder（基础审计 + token 归因，非 langfuse 级）；InjectionGuard（模式匹配，覆盖不了语义改写/编码混淆）。
- review-before-write **已实现**（M9）：`Tool.preview` dry-run + `ToolRegistry` 在 ASK 时返回 diff 预览+`awaitUser` 不落盘；`AgentLoop.pendingCall` 携带待审批调用；`CodeAgentCli.runUntilDone` 把 diff 打到终端问 `Approve? [y/N]`，批准才落盘并持久化、拒绝则反馈给模型。`--accept-edits` 跳过询问。

## ★ 中间件知识点的诚实口径（用户 2026-09-16 明确需求，已写入 `docs/RESUME.md` 第七节）
用户要「服务器依赖中间件的高水平知识点写进简历」。本项目**刻意零中间件**（JSONL 替代 MySQL、进程内 BM25 替代 ES/向量库、stdio 子进程替代 gRPC），所以**绝不能**写「用了 Redis 锁 / RocketMQ 异步」这类 bullet。
正确写法 = 写「**中间件选型能力**」：论证不同规模下该引入什么、为什么，不引入时靠什么兜住。覆盖 7 块：
1. 持久化：会话是事件流不是实体表；何时换 MySQL → Event Sourcing + CQRS 快照读模型
2. 检索：字面匹配用 BM25，语义型才上向量 HNSW；工业标准 = BM25+向量混合召回 + cross-encoder rerank
3. Redis 四用法：同会话互斥锁（**Fencing Token 补 GC pause 下锁失效**）、requestId 幂等键（SET NX EX 原子）、热会话缓存（击穿/穿透/雪崩）、流式输出 Stream vs Pub/Sub
4. MQ：何时该上；三段式不丢（confirm/持久化/手动 ack）+ 幂等消费 + 延迟队列 + 死信
5. 通信：stdio vs gRPC；**stdio 缓冲区打满导致双向死锁**必须开独立读线程（本项目已实现）
6. 可观测：JSONL trace → OpenTelemetry + Prometheus；trace/metric/log 分工；高基数标签不能进 metric
7. ★ Agent 特有风险：密钥只从环境变量读——Agent 会把文件内容读进上下文，key 写进配置文件会被 `read_file` 带进 prompt→会话日志→Trace→模型厂商，**不可逆多点泄露**

## M13 里程碑（2026-09-17 完成，PRD 见 `docs/PRD-M13.md`）
- **P0-1 评测集扩 30 条**：`evalset/basic.jsonl` 4→30 条（纯数据，t1–t4 不变；6 工具各 ≥2 次 + 4 条安全类）；`EvalTest` 新增「加载真实文件/条数/id 唯一/覆盖度/端到端聚合」13 条断言（新增 `evalRouter` 确定性路由模型 + `resolveEvalPath`）。
- **P0-2 Coordinator 修缺陷**：原实现「子 Agent 失败被静默吞掉」→ 现 `chatOnce` 返回完整 `AgentTurnResult`；单步失败重试 1 次、失败标 `[FAILED]`、`execute` while 循环在 `maxReplans`（默认 1）后停止重规划。`WorkflowTest` 加 `ProgrammableModel` + 4 条断言。
- **P1-1 RAG lexical rerank**：新增 `rag/Reranker.java`（覆盖度×100 + 标题命中 50 + 精确短语 30，稳定 tiebreak）；`CorpusIndexer.setReranker` / `RagProvider.enableRerank`，**默认关闭**；`RagTest` 加 9 条（D1–D6）。
- **P1-2 MCP 真子进程**：新增 `test/McpEchoServerFixture.java`（stdio JSON-RPC 回声 server）+ `McpTest` 真子进程 5 条断言（握手/echo/add/**连续 60 次无死锁**/shutdown 干净退出），环境受限时打印 SKIP 不 FAIL。
- **P0-3 文档同步**：`README.md` / `docs/RESUME.md` / 本文件全部改为 **249 断言 / 64 主源文件 / eval 30 条 / 含 lexical rerank / 含重试重规划**；`PRD-M13.md` 顶部加「已实施完成」状态横幅。
- **P2-1（MCP resources 只读）按 PRD 明确不做**。
- 测试期间踩坑：① `ProgrammableModel` 用 `contains("step ")` 判定角色，被 synthesize 回灌的 context（含 `Step 1:`）误判成 executor → 改**按消息前缀 `startsWith` 判定**；② `Reranker` 标题命中初版只判「首行含查询词」，导致正文首行也误命中 → 改**要求首行以 markdown `#` 开头**；③ fixture 里嵌套 `new LinkedHashMap<>()` + `Map.of` 泛型推断坑 → 用显式类型参数 / 局部变量。

## 环境事实
- GLM 资源包已生效（2026-12-13 到期）：glm-4.6v 赠送 599 万 token、glm-4.5-air 1195 万 token。默认模型已切 `glm-4.6v`（不再 429）；`--model glm-4.5-air` 更省、适合纯 coding。真实调用需本地设 `CODEAGENT_API_KEY`，沙箱/本会话无 key。离线 `--mock` 全通。
- GitHub push 间歇性 `CONNECT tunnel failed 502`，需重试；失败先本地 commit 勿丢。
- **GitHub PAT 曾在对话中明文暴露，建议吊销重生成**。
- **GLM API Key 也在本会话明文暴露过两枚**：① 早期一枚调用 `open.bigmodel.cn` 返回 **HTTP 401「令牌已过期或验证不正确」**（key 本身无效/过期），**必须吊销**；② 用户 2026-09-16 新给一枚（`9851e74c...`）经 Smoke 实测 **HTTP 200 有效**（`glm-4.6v latency≈2.8s`），真实端到端 eval 也能跑通——但同样已明文暴露，**建议用毕即吊销重生成**。本项目代码只读 `CODEAGENT_API_KEY` 环境变量，从不落盘；所有真实调用仅在本会话运行时传入、不写入任何文件。
