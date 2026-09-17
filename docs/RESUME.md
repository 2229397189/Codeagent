# CodeAgent · 简历映射与面试问答（诚实版）

> 使用原则：**每条 bullet 都能指到具体文件**。面试被追问时，能说出「在哪个类、为什么这么写、边界在哪」。
> 红线：本项目是**参考开源设计思想、用 Java 从零实现**，绝不说「复刻/实现了 XX 全部能力」。第七节「中间件选型」是本项目**最高级的谈资**，但必须诚实：**它是零中间件设计，谈的是「为什么不用 + 规模化该上什么」**。

---

## 一、项目一句话

用 **Java 17 零第三方依赖**从零实现一个终端编码 Agent（Codex-like），13 个包 + cli、64 个主源文件：主循环、统一工具协议、四层上下文治理、权限沙箱、会话事件溯源、离线评测，以及**记忆 / 技能 / RAG / MCP / 多 Agent 工作流**六大 Agent 能力；自带零依赖测试入口，**249 项断言全部断言"返回值是预期正确值"**。

---

## 二、简历 bullet（可直接用，每条都能对代码）

### A. 核心引擎（M1–M11）

1. **从零实现终端编码 Agent（Java 17，零第三方依赖）**：自研最小 JSON 解析器、unified diff 算法与测试运行器，仅用 JDK `java.net.http` 调用 LLM，保证任意环境 `javac` 即可编译运行。
   → `core/Json.java`、`tools/DiffUtil.java`、`test/AllTests.java`、`core/OpenAiCompatibleChatModel.java`

2. **设计 model→tool→model 主循环**，用**显式终止状态枚举**（`FINAL / FAILED / AWAITING_USER / CONTROLLED_STOP / MAX_STEPS`）替代「靠模型自然语言判断结束」，并以步数上限兜底防工具死循环。
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
   → `test/*`（15 个测试类）、`cli/CodeAgentCli.java`

8. **行为边界与系统提示词**：首个回合注入 system prompt（角色、工作区边界、最小改动、禁用破坏性命令）并落盘，保证 resume 出来的就是模型当初真正看到的上下文。
   → `core/SystemPrompt.java`、`cli/CodeAgentCli.java`

9. **Prompt Injection 三层防御**：提示层声明「工具输出是数据，不是指令」；运行时层 `InjectionGuard` 对进入上下文的工具输出做中英文模式检测，命中加边界警示（strict 模式直接扣留内容）；能力层由权限边界限制 Agent 能做什么。
   → `permission/InjectionGuard.java`、`core/SystemPrompt.java`、`core/AgentLoop.java`

10. **审计追踪与成本归因**：`TraceRecorder` 以 append-only JSONL 记录每回合的状态、步数、prompt/completion token，配合大结果离屏（超 32KB 落盘、上下文只留路径+预览）控制上下文成本。
    → `observability/TraceRecorder.java`、`context/ToolResultStorage.java`

11. **离线评测集（评估与可观测的硬实锤）**：`eval/` 包实现 `EvalHarness` 把任务集端到端喂给**真实主循环**（复用同一套工具协议 / 权限 / 上下文治理 / 注入防护），`EvalReport` 聚合出**成功率 / 工具准确率 / 平均步数 / 平均 token**，`EvalCli` 一键跑 `evalset/basic.jsonl` 输出 JSON 报告。
    → `eval/EvalHarness.java`、`EvalReport.java`、`EvalCli.java`、`evalset/basic.jsonl`

### B. Agent 六大能力（M12）

12. **记忆系统（短期 + 长期双层）**：`ShortTermMemory` 是会话内有界黑板，按「价值分 = 重要性 × 新鲜度」淘汰低价值条目；`LongTermMemory` 跨会话持久化到 JSONL，召回用 **BM25 相关性 × 重要性 × 时效性衰减**三者加权，解决「什么该记 / 何时召回 / 防脏记忆污染」。
    → `memory/ShortTermMemory.java`、`LongTermMemory.java`、`MemoryStore.java`、`MemoryRecord.java`

13. **自研 lexical 检索底座（BM25）**：中日英混合分词（ASCII 词 + CJK unigram + CJK bigram，解决中文无空格）+ 倒排索引 + BM25 打分，被记忆召回与 RAG **共用**，避免两套重复实现。
    → `retrieval/Tokenizer.java`、`InvertedIndex.java`、`Bm25.java`

14. **技能（Skills）路由式专业化**：不把所有指令塞进一条超长 system prompt，而是把常用任务（code-review / explain / refactor）写成技能，按用户输入**触发词打分路由**到最匹配的技能再注入其指令，并支持从 `.codeagent/skills/*.md`（frontmatter 格式）热加载自定义技能。
    → `skills/Skill.java`、`SkillRegistry.java`

15. **RAG（基础版，诚实标注为 lexical RAG）**：文档切分（空行分段 + 超长段落滑动窗口重叠切分）→ 建索引 → BM25 召回 → **lexical 重排（`Reranker`：查询词覆盖 / 标题命中 / 精确短语，纠正 BM25 纯词频偏置，默认关闭可开关）** → 渲染为带来源的上下文块注入 prompt。**没做 embedding/向量库，rerank 也是 lexical 特征重排而非 cross-encoder**，见第七节「什么时候该上向量库」。
    → `rag/Chunker.java`、`Chunk.java`、`Document.java`、`CorpusIndexer.java`、`Reranker.java`、`RagProvider.java`

16. **MCP 客户端（零依赖 JSON-RPC 2.0 over stdio）**：自研 `JsonRpcClient`（自增 id 匹配响应、独立 daemon 读线程**规避 stdio 缓冲区打满导致的双向死锁**），完成 initialize → notifications/initialized → tools/list 握手，把远端 `tools/call` 结果适配成本地 `Tool` 接口，**复用同一套权限门与上下文治理**；`McpLauncher` 用 `ProcessBuilder` 起子进程。
    → `mcp/JsonRpcClient.java`、`McpClient.java`、`McpTool.java`、`McpLauncher.java`、`EmbeddedMcpServer.java`（进程内测试桩）

17. **多 Agent 工作流（Plan-and-Execute）**：`Coordinator` 拆成 planner / executor / synthesizer 三个子 Agent，**每个子 Agent 都是一次独立的 `AgentLoop` 回合**，因此多 Agent 协作仍受同一套权限与上下文治理约束，不是「另开一个不受控的模型」；`Plan.parse` 对模型输出做**健壮解析**（剥 ```json 围栏、抠最外层 JSON、任何脏输入退化为空计划走单步直答）；**单步失败自动重试 1 次，仍失败则标记 `[FAILED]` 并把失败信号回灌 planner 触发一次重规划（`maxReplans` 硬截断）**，避免子 Agent 失败被静默吞掉。
    → `workflow/Coordinator.java`、`Plan.java`

---

## 三、面试高频追问（牛客 Agent 岗）与回答要点

| 追问 | 回答要点 |
|------|---------|
| 为什么用 provider 的 usage 记账，而不是自己数 token？ | 自己数会和 BPE/真实计费口径不一致，且与 provider 实际窗口不符；用 provider 返回值最准。压缩后旧 usage 会失效，所以 `Usage` 里有 `stale` 标记。 |
| micro-compact 为什么保留 `read_file` 的结果？ | 参考材料删了要重读（再花一轮 token 和延迟），不如留着；被裁剪的是「用过即弃」的 grep/list 结果。裁剪是**确定性**的（可回归测试），不依赖模型。 |
| 权限为什么是三态（ALLOW/DENY/ASK）而不是布尔？ | 布尔无法表达「需要人来拍板」。写操作默认 ASK，审批后落盘持久化；DENY 用于明确危险（逃逸/危险命令），不给人误点的机会。 |
| 会话为什么用 append-only 事件溯源？ | 崩溃后可 replay 精确恢复；fork 就是复制日志，天然隔离；审计友好。代价是文件会增长，靠 compact 治理。 |
| maxSteps 兜底会不会截断正常任务？ | 会，这是**有意的降级**：宁可返回 `MAX_STEPS` 让上层/人接手，也不让 Agent 无限烧 token。 |
| 工具结果为什么要离屏？ | 「系统能访问完整数据」≠「要把完整数据塞进 prompt」。完整内容落盘，上下文只放路径+预览，需要时再取。 |
| 长期记忆怎么防「脏记忆污染」？ | 三道闸：①写入要带重要性与来源（不写模型推断的东西）；②召回是**加权打分**不是全量注入，只有真相关的才进 prompt；③支持 `forget` 定向删除。核心是**宁可少记，不要记错**。 |
| 为什么 BM25 而不是向量？ | 代码检索的查询词是标识符/报错串，是**字面匹配**问题，BM25 的 IDF 天然惩罚 `public`/`get` 这类高频词；且零依赖可离线。语义型查询才需要向量（见第七节）。 |
| MCP 为什么用 stdio 子进程？ | 要的是**零配置、跨平台、进程级隔离**：子进程崩了主进程不死，还能用 OS 权限约束。跨机/多租户才需要 HTTP 或 gRPC。 |
| 多 Agent 会不会失控？ | 我们把每个子 Agent 都跑在同一个 `AgentLoop` + 同一个 `PermissionManager` 上，**权限与上下文治理不因为「多 Agent」就绕过**；计划还有 `maxPlanSteps` 截断。 |

---

## 四、字节 Agent 岗 JD 映射（第 4、5 条）

| JD 要求 | 本项目对应 | 诚实边界 |
|---------|-----------|---------|
| RAG | bullet 15：`rag/` 包完整链路（切分→索引→BM25 召回→lexical 重排→注入） | **基础版 lexical RAG**，无 embedding / 无向量库；rerank 是 lexical 特征重排（非 cross-encoder） |
| Prompt Engineering | bullet 8 + 14：`SystemPrompt` 边界注入 + Skills 路由式指令（把长 prompt 拆成按需加载的技能） | 没有做 prompt 的 A/B 评测（评测集只测工具与成功率） |
| Function Calling | bullet 4：`ToolSpec` 声明 + `ToolResult` 标志位驱动主循环 | 用的是 OpenAI 兼容的 tool_calls 协议，没做多模型 schema 适配层 |
| 多 Agent 协作框架 | bullet 17：`Coordinator` 的 Plan-and-Execute（planner/executor/synthesizer + 单步重试 + 一次重规划） | **没用 LangGraph/AutoGen**，是参考其思路的从零实现；子 Agent 串行执行，无并行、无 Reflection 自我纠错 |
| 深入理解 OpenClaw / Claude Code / Cursor / Copilot | 第三节 + 第七节：能讲清这些产品共同的四条设计主线（**上下文治理 / 工具协议 / 权限边界 / 可观测**），并说出我们对应在哪、差在哪 | 是**设计理解**，不是「参与开发」；面试绝不能说复刻 |

**一句话总结口径**：「我没有用 LangChain/AutoGen，是**先自己实现最小 Agent Runtime，再回头看框架源码**，所以我能说出每个抽象是为了解决什么问题、以及我不想付的代价是什么。」

---

## 五、明确没做（面试别吹，避免过度宣称）

- ⚠️ **RAG 是基础版**：已实现 lexical（BM25 + lexical rerank）全链路，但**没有 embedding、没有向量库；rerank 是可解释的 lexical 特征重排（查询词覆盖 / 标题命中 / 精确短语），不是 cross-encoder**。面试说「基础版 lexical RAG」。
- ⚠️ **多 Agent 是基础版**：Plan-and-Execute 已跑通，**含单步失败重试（1 次）与一次重规划**，但**子 Agent 串行执行、无并行、无 Reflection 自我纠错**。
- ⚠️ **MCP 只做了客户端**：能连外部 MCP server 并调用其工具，**没有实现 MCP server 端、没有资源（resources）与提示（prompts）能力**。
- ❌ **没有 Redis / MySQL / MQ**：所有持久化是本地文件（JSONL）。这是**刻意的设计选择**，理由见第七节。
- ⚠️ **Prompt Injection 是「基线方案」不是「完整方案」**：模式检测 + 边界警示（中英文常见注入句式，strict 模式可扣留），**无法覆盖语义改写 / 编码混淆**。
- ⚠️ **TraceRecorder 是基础审计**：记录状态/步数/token 用于成本归因，**不是 langfuse 级的 tracing + eval 平台**。
- ✅ **离线评测集已实现**（`EvalHarness` + `EvalReport`，数据集 `evalset/basic.jsonl` **30 条**，覆盖 6 个内置工具各 ≥2 次 + 4 条安全类；`EvalTest` 断言加载真实文件 / id 唯一 / 覆盖度 / 端到端聚合）。

---

## 六、可量化的 project facts（面试随口能报）

- 语言/依赖：Java 17，**0 个第三方依赖**
- 规模：**13 个包 + cli / 64 个主源文件**（core / tools / context / permission / session / observability / eval / retrieval / memory / skills / rag / mcp / workflow + cli）
- 工具数：6 个内置 + 任意 MCP 外部工具
- 测试：**249 项断言全绿，15 个测试类**（含 1 个真实子进程 MCP 用例：独立 JVM 跑 JSON-RPC 回声 server，连续 60 次请求验证 stdio 无死锁）
- 上下文水位：70% warn / 90% auto / 100% hard（对齐 Codex）
- 大结果离屏：预览 200 字符 + 完整落盘路径
- 记忆：短期有界黑板（价值淘汰）+ 长期 JSONL（BM25 × 重要性 × 时效性衰减）
- 多 Agent：planner / executor / synthesizer，`maxPlanSteps` 默认 8、每步 `maxExecSteps` 默认 12

---

## 七、中间件选型能力（★ 本节是本项目最高级的谈资）

### 7.0 先立住诚实口径

> **CodeAgent 是零中间件设计**——持久化用 JSONL 文件、检索用进程内 BM25、通信用 stdio 子进程。
> 所以简历上**不能**写「用 Redis 做分布式锁」「用 RocketMQ 异步解耦」。
> 正确写法是写**中间件选型能力**：*「能论证一个 Agent 系统在不同规模下该引入什么中间件、为什么，以及不引入时靠什么机制兜住。」*
> 这比堆砌名词高级得多——它证明你知道**每个中间件解决的是什么问题**，而不是只会配。

**可直接用的简历 bullet：**

> 18. **负责 Agent 系统的中间件选型论证**：在单机 CLI 形态下刻意采用**零中间件**架构（JSONL append-only 事件溯源替代 MySQL、进程内 BM25 替代 Elasticsearch/向量库、stdio 子进程隔离替代 gRPC），并给出规模化演进路径：多实例共享态引入 Redis 做**同会话互斥锁 + requestId 幂等键 + 热会话缓存**，长任务引入 MQ 做**可靠异步 + 幂等消费**，语义检索演进为 **BM25 + 向量混合召回 + rerank**，服务端化后接入 OpenTelemetry 做 span/metric 采集。

---

### 7.1 持久化：为什么用 append-only JSONL 而不是 MySQL

| 维度 | 本项目选择 | 理由 / 面试点 |
|------|-----------|--------------|
| 数据模型 | 会话是**事件流**，不是实体表 | 一行 JSON = 一个事件，append-only + replay 天然满足**崩溃恢复**（replay 到崩溃点）、**fork**（复制日志即隔离）、**审计**（不可篡改）三件事；零 schema migration 成本。 |
| 并发 | 用数据模型规避并发 | append-only 没有「更新同一行」的写冲突；单机单进程不需要行锁。**这是设计选择，不是没考虑并发。** |
| 何时必须换 MySQL/PG | ① 多端/多设备共享会话 ② 需要按用户·项目做查询与运营分析 ③ QPS 高到单机文件锁/刷盘成为瓶颈 | 演进方案：事件表（`session_id` 索引）+ **定期 compaction 出快照表**，读时「快照 + 增量事件」而不是每次全量 replay——这就是 **Event Sourcing + CQRS 读模型**，面试要能把这两个词接上。 |
| 坑 | 日志无限增长 | 靠 compact 治理；MySQL 侧靠归档冷数据 + 分区表。 |

**面试官追问「为什么不一开始就上 MySQL」**：因为此时上数据库是**负债不是资产**——要为单机场景付连接管理、连接池、schema 演进、部署依赖的成本，换来的查询能力当前一个用户都用不上。**选型的本质是选「当下不付的代价」和「未来要付的迁移成本」哪个更贵。**

---

### 7.2 检索：为什么用进程内 BM25 而不是 Elasticsearch / 向量库

| 维度 | 说明 |
|------|------|
| 查询性质 | 代码检索的查询词是**标识符 / 报错串 / 符号名**，本质是**字面匹配**问题。BM25 的 IDF 天然惩罚 `public`、`get`、`String` 这类高频噪声词。 |
| 中文分词 | 中文没空格，必须 **CJK unigram + bigram** 一起上（unigram 保召回、bigram 保精度），否则「上下文治理」会被切成一个不可匹配的整串。→ `retrieval/Tokenizer.java` |
| 成本 | 进程内、零依赖、可离线、毫秒级；ES 为一个 CLI 工具引入 JVM 外的整套服务不划算。 |
| 何时上向量库 | 查询变成**语义型**（「在哪里处理重试」）时，字面匹配失效，需要 embedding + **HNSW 近似最近邻**。 |
| 工业标准做法 | **BM25 + 向量混合召回 → cross-encoder rerank 精排**。BM25 保精确召回（不漏标识符），向量保语义泛化，rerank 用 cross-encoder（query 与 doc 联合编码）而非 bi-encoder（各自编码，快但精度低）。我们 `RagProvider.retrieve` 就是这个位置的扩展点。 |
| 面试加分点 | HNSW 的 **recall / latency 权衡**（`efSearch` 越大越准越慢）；IVF-PQ 的**量化损失**（内存换精度）；为什么混合召回几乎总是优于纯向量。 |

---

### 7.3 缓存与锁：为什么没上 Redis，以及什么场景必须上

**为什么不上**：单机 CLI 进程内 `HashMap` 就够。Redis 的唯一不可替代价值是**多实例共享状态**——当前形态下没有多实例，上 Redis 只是增加一个进程和一个故障点。

**规模化后 Redis 在 Agent 系统里的四个真实用法**（这段是全节最值钱的部分，直接对应你另一条项目经验，可复用）：

| 用法 | 场景 | 面试点 |
|------|------|--------|
| ① **同会话互斥锁** | 多实例下同一 session 的两个回合并发执行，会写同一个文件 / 同一份记忆 | Redisson 看门狗自动续期；**锁在 GC pause / 网络抖动下会失效** → 必须配 **Fencing Token**（单调递增序号，存储侧校验），否则仍会写出脏数据 |
| ② **requestId 幂等键** | 工具是有副作用的（写文件、跑命令），重试/重放不能执行两次 | `SET key value NX EX ttl` 原子抢占（或 Lua 保证原子），**绝不能 GET 再 SET**（非原子，并发下都拿到空）；value 存 requestId 便于释放时校验「只能删自己的锁」 |
| ③ **热会话缓存** | 避免每次 resume 全量读 JSONL | 缓存三兄弟在 Agent 场景的对应：**击穿**（热点会话缓存过期瞬间大量并发回源 → 互斥重建 / 逻辑过期）、**穿透**（查不存在的 sessionId → 布隆过滤器或缓存空值）、**雪崩**（大量 key 同时过期 → TTL 加随机抖动） |
| ④ **流式输出 Pub/Sub** | 多端（Web / CLI）订阅同一个 run 的输出流 | Redis Stream 比 Pub/Sub 更适合（Pub/Sub 无持久化，断连即丢） |

---

### 7.4 异步与消息：为什么是同步阻塞，什么时候该上 MQ

**为什么不上**：终端 Agent 的交互是**一问一答、人在等结果**。引入 MQ 只会增加延迟和不确定性，且单机没有削峰需求。

**该上的场景**：
- **长任务异步化**：全仓索引 / 跑测试套件 / 批量重构，提交后返回 jobId，后台执行 + 状态机 + 结果回调。
- **工具副作用可靠执行**：Exactly-once 做不到（网络分区下无法证明），工业做法是 **At-least-once + 消费侧幂等**。

**面试点（三段式不丢消息）**：① 生产者 **confirm / 事务** 保证发出去；② Broker **持久化**（刷盘 + 副本）；③ 消费者 **手动 ack**（处理完再 ack，不能收到就 ack）。重复消费靠**幂等表 / 唯一键**兜底；超时重试用**延迟队列**；失败多次进**死信队列**人工介入。

---

### 7.5 通信：MCP 为什么用 stdio 而不是 gRPC / HTTP

- **要的是零配置 + 跨平台 + 进程级隔离**：stdio 天然隔离（子进程崩了主进程不死），还能用 OS 级权限约束子进程。
- **真实工程坑（我们实现里踩过并解决）**：stdio 是**有界缓冲区**的——如果主进程只在 `request` 时同步读 stdout，而子进程输出填满缓冲区后阻塞写，主进程又在等响应 → **双向死锁**。解法是**起独立 daemon 线程持续消费 stdout/stderr**（`mcp/JsonRpcClient.java`），请求侧靠 **自增 id 匹配响应**做异步转同步。这个点说出来，比背 RPC 概念有说服力得多。
- **何时换网络协议**：工具服务需要跨机部署 / 多租户 / 鉴权与限流 → HTTP+SSE 或 gRPC（需要流式、IDL、多路复用）。

---

### 7.6 可观测：JSONL trace vs Prometheus / langfuse

- **当前**：结构化事件落 append-only JSONL（run → turn → tool call 三层天然构成 span 树），单机够用，且是**评测集的数据来源**。
- **服务端化后**：接 **OpenTelemetry**（trace 链路、metric 聚合、log 关联）+ Prometheus/Grafana；Agent 特有指标：**成功率 / 工具准确率 / 事实正确率 / 格式通过率 / 成本·P95 延迟 / 人工接管率**。
- **面试点**：trace / metric / log 三件套分工——trace 回答「这次为什么慢/错」，metric 回答「整体趋势与告警」，log 回答「当时具体发生了什么」；高基数标签（如 sessionId）不能进 metric 维度（会打爆时序库），只能进 trace。

---

### 7.7 ★ Agent 特有的安全风险：密钥为什么只从环境变量读

这是**最有水平的一条**，而且是本项目真实落地的决策（`AgentConfig` 只读 `CODEAGENT_API_KEY` 环境变量，从不落盘）：

> **传统服务**：密钥写配置文件，风险限于「文件泄露」。
> **Agent 系统**：Agent 会**把文件内容读进上下文**。一旦 API Key 写进 `codeagent.properties`，只要 Agent 一次 `read_file` 读到它，这个 key 就会进入 **prompt → 会话 JSONL 日志 → Trace 记录 → 可能的模型服务商日志**，是**不可逆的多点泄露**。

**正确做法**：密钥只从环境变量 / 密钥管理（Vault / KMS）注入；对 Agent 可见的文件系统做**敏感文件黑名单**（`.env`、`*.pem`、`credentials`），工具层直接 DENY。这条一说，面试官会认为你真的想过 Agent 的安全边界，而不只是抄了份八股。

---

## 八、推荐的下一步（按面试收益排序）

1. ✅ diff 预览 + 审批（review-before-write）—— 已完成
2. ✅ 离线评测集（`EvalHarness` + `EvalReport`）—— 已完成，**已扩到 30 条任务**并断言覆盖度/聚合，形成完整闭环
3. ✅ 六大 Agent 能力（记忆 / 技能 / RAG / MCP / 多 Agent / 检索底座）—— 已完成（基础版）
4. ✅ rerank 环节已做成 **lexical 特征重排**（`rag/Reranker.java`，默认关闭可开关，纠正 BM25 词频偏置）——但**向量召回 + cross-encoder 精排尚未做**（需 embedding 服务 / 向量库，超出零依赖范围）。**下一步**仍是：把「Redis 幂等键 + Fencing Token」真的做出来，或补齐向量混合召回，让第七节从「论证」升级为「论证 + 实证」。
5. 再往后：Reflection 自我纠错、并行子 Agent、MCP server 端。
