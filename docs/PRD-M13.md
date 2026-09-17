# PRD-M13 · CodeAgent 增量迭代（补齐 + 裁剪）

> ✅ **状态：已实施完成（M13）**。本文档中的「基线 215 断言 / 67 源文件」「样例 4 条」「无 rerank」「无失败重试与重规划」等表述均为**开工前（M12 基线）的历史记录与验收标准**，不代表当前实现。
> 完成后实际结果：**249 断言全绿 / 64 主源文件 / eval 30 条 / RAG 含 lexical rerank / Coordinator 含单步重试 + 一次重规划**。当前口径以 `README.md` 与 `docs/RESUME.md` 为准。
>
> **需求来源**：用户原话「实现所有功能 和测试功能」（含糊、延续性诉求），结合 `docs/RESUME.md` 第五节「明确没做 / 只是基础版」清单盘点得出候选缺口 A–F。
> **基线**：M12（commit `849fe0c`）—— 13 包 / 67 源文件 / 215 断言 / 15 测试类全绿。
> **Language**：中文 ｜ **Project Name**：`codeagent_m13`
> **技术栈（不可改）**：Java 17 + JDK 标准库，零第三方依赖，`javac` 直接编译。

---

## 一、产品目标（一句话）

**在不破坏「零依赖 + 简历诚信」两条底线的前提下，把 M12 遗留的三处「静默缺陷 / 空头承诺」补成可断言的实锤（评测集 30 条、工作流失败重试与重规划、文档口径一致），其余候选缺口一律裁剪。**

---

## 二、约束（★★★ 硬性，违反即验收不通过）

### C1 · 极简优先，严禁功能蔓延
用户历史强偏好：「纠正过度设计 / 功能蔓延，要求工具设计回到极简聚焦核心需求最小可用形态」。
因此本 PRD 的**首要产出是「不做清单」（第五节）**，而不是需求列表。判定规则：
- 能用**数据**（如评测任务）解决的，不写**代码**；
- 能**修缺陷**的，不**加能力**；
- 需要**新增类 / 新增子 Agent / 新增协议面**的，一律降级到 P1/P2 或直接砍掉；
- 不新增配置项（除必要的硬截断阈值外）、不新增文件、不新增 schema 字段。

### C2 · 诚信红线
本项目简历口径是「绝不写没做的东西」。
- 任何新能力落地后，**必须**在 `docs/RESUME.md` 标注为「基础版」并写明边界（不做这件事 = P0-3 未完成）；
- 若某能力做了会让简历变成注水（例如零依赖项目里硬塞 Redis / 引入外部 embedding 服务却宣称"自研向量召回"）——**直接砍掉**，不做；
- 已在简历上、但**从未被真实路径验证过**的能力（如 MCP 真实子进程），属于「空头承诺」，优先补验证而不是补功能。

### C3 · 零依赖铁律
Java 17 + JDK 标准库，**不允许引入任何第三方依赖**（不得新增 Maven/Gradle 构建，不得引 langchain / okhttp / jackson / 任何向量库 client / 任何 embedding SDK）。所有实现必须 `javac -encoding UTF-8` 直接编译。

### 全局 DoD（每个 P0 完成都必须同时满足）
| # | 验收 |
|---|------|
| G1 | `javac -encoding UTF-8 -d out src/main/java/com/codeagent/**/*.java src/test/java/com/codeagent/**/*.java` 编译通过 |
| G2 | `java -cp out com.codeagent.test.AllTests` 输出 `ALL TESTS PASSED`，**原有 215 条断言零回归**（只允许净增） |
| G3 | 全量源码 `import` 只允许 `java.*` 与 `com.codeagent.*` |
| G4 | 新增测试**必须断言返回值是预期的正确值**，不接受「不抛异常就算过」 |
| G5 | RESUME / README 的规模数字与代码实际一致（见 P0-3） |

---

## 三、需求池

### P0（本轮必做）

---

#### P0-1 · 离线评测集扩到 30 条（纯数据，零新增类）

**需求描述**
`evalset/basic.jsonl` 现有 4 条样例，RESUME 第五节写「扩到 30 条即完整闭环」——这是**性价比最高的一步**（只加数据、不改框架）。补齐到 30 条，覆盖 6 个内置工具 + 安全边界 + 终止行为。**不新增 `EvalTask` 字段、不改 `EvalHarness`、不新增评测集文件。**

**用户故事**
> As a 准备字节 Agent 岗面试的候选人, I want 一个 30 条的端到端评测集, so that 简历上「离线评测集」这条 bullet 从「⚠️ 基础版」升级为「✅ 完整闭环」，且面试能报出「30 条任务的成功率/工具准确率/平均步数」。

**验收标准**
| # | 断言 |
|---|------|
| A1 | `EvalHarness.loadTasks("evalset/basic.jsonl")` 返回 **≥ 30** 条任务，且**全部解析成功**（无一行抛 `IllegalArgumentException`） |
| A2 | 30 条 **id 唯一**；已有 `t1`–`t4` 的 id 与内容**保持不变**（不破坏既有断言与引用） |
| A3 | **≥ 27 条（90%）** 显式设置了 `expectTool` 或 `expectContains`；仅 ≤ 3 条纯开放问答以「抵达 `FINAL`」为通过条件 |
| A4 | 工具覆盖度：6 个内置工具（`read_file` / `grep` / `list_files` / `edit_file` / `patch` / `run_command`）**每个至少 2 条**任务指定其为 `expectTool` |
| A5 | 安全/边界类任务 **≥ 3 条**（越权路径读取、危险命令执行等），验证 Agent 被 `DENY` 后仍能抵达 `FINAL` 而非崩溃 |
| A6 | 用确定性模型跑全量 30 条：`EvalReport.total == 30`，`summary()` 输出非空，且**不抛异常** |
| A7 | **不以成功率作为验收门槛**：mock/真实模型下的成功率反映模型能力而非框架正确性（M11 实测真实 GLM 仅 25%，属模型弱点）。**禁止**为抬高成功率而削弱断言 |

**任务分布建议（30 条）**
| 类别 | 条数 |
|------|------|
| `read_file` | 4 |
| `grep` | 4 |
| `list_files` | 3 |
| `run_command` | 4 |
| `edit_file` | 4 |
| `patch` | 3 |
| 纯问答（只读，不调工具，`expectContains`） | 3 |
| 安全/边界（越权、危险命令） | 3 |
| 长任务（`maxSteps` 边界） | 2 |

**涉及文件**
- `evalset/basic.jsonl`（新增 26 行数据）
- `src/test/java/com/codeagent/test/EvalTest.java`（增加载真实文件 / 条数 / id 唯一 / 覆盖度 / 聚合断言）

---

#### P0-2 · Coordinator 步骤失败重试 + 一次重规划（修静默吞失败的缺陷）

**需求描述**
当前 `Coordinator.executeStep` → `chatOnce` 只取 `lastText(msgs)`：子 Agent 若未达 `FINAL`（`FAILED` / `MAX_STEPS` / 空输出），返回空串或部分文本，而 `context` 照写 `Result: `，synthesizer 基于残缺结果综合——**失败被静默吞掉**，且无重试、无重规划。这是缺陷不是新功能。

最小修复范围（**不引入 critic 子 Agent，不做并行**）：
1. `chatOnce` 改为返回 `AgentLoop.AgentTurnResult`，让 Coordinator 能读到 `status`；
2. 单步失败（`status != FINAL` 或结果为空串）→ **重试 1 次**（该步最多 2 次尝试）；
3. 仍失败 → 该步在 `context` 中标记为 `[FAILED]`，并触发**一次重规划**：调用 planner（带上已完成步骤与失败原因）产出新计划，**只执行剩余未完成步骤**；
4. `maxReplans` 硬截断（默认 1，即 planner 总调用数上限 = 2），防死循环。

**用户故事**
> As a 使用 `--workflow` 跑多步任务的用户, I want 某一步执行失败时系统能自动重试、必要时重规划而不是拿残缺结果糊弄我, so that 多 Agent 编排在子 Agent 失手时仍可收敛，我也能从 `[FAILED]` 标记看清哪一步出了问题。

**验收标准**
| # | 断言 |
|---|------|
| B1 | **零回归**：全部步骤成功时，planner 调用数 == 1、step 执行数 == 3（沿用现有 `WorkflowTest` 断言，不得破坏） |
| B2 | 单步失败：脚本化模型让 STEP 2 首次返回非 `FINAL` → 该步 `executeStep` 被调用 **2 次** |
| B3 | 重试仍失败 → 触发重规划：planner **被调用 2 次** |
| B4 | 重规划上限：即使重规划后的新计划仍失败，planner 总调用数 **== 2**（不触发第 3 次） |
| B5 | 失败步骤在传给 synthesizer 的 context 中带 `[FAILED]` 标记（可被断言） |
| B6 | `maxPlanSteps` 截断在**重规划前后均生效**（重规划后总执行步数仍 ≤ `maxPlanSteps`） |
| B7 | 现有 **3 参 / 5 参**构造器签名保持不变（新增 6 参重载以传 `maxReplans`），`WorkflowTest` 无需改签名即可通过 |

**涉及文件**
- `src/main/java/com/codeagent/workflow/Coordinator.java`（主改动）
- `src/test/java/com/codeagent/test/WorkflowTest.java`（扩展 `ScriptedModel` 以模拟失败 / 断言重试与重规划次数）

---

#### P0-3 · 文档与简历口径同步（诚信红线收口）

**需求描述**
C2 要求「新能力必须标注基础版 + 边界」。本项把 P0-1 / P0-2（及若做的 P1）的口径变化落到 `docs/RESUME.md` 与 `README.md`，并更新规模数字。这不是"写文档"，是**防止简历注水**的强制步骤。

**用户故事**
> As a 要在面试里讲这个项目的候选人, I want 文档里每一条「没做 / 基础版」都与代码真实状态一致, so that 我被追问时不会因为文档过期而说出不实陈述。

**验收标准**
| # | 断言 |
|---|------|
| C1 | `docs/RESUME.md` **不出现**与实现矛盾的表述：完成 P0-2 后不得残留「无失败重试与重规划」；完成 P0-1 后不得残留「样例 4 条…扩到 30 条即完整闭环」（应改为已完成 + 实际条数） |
| C2 | 本轮新增/变更的每一项能力，在 RESUME 中均带**「基础版」标注 + 明确边界句**（例：rerank 若做，须写「lexical 特征重排，**非** cross-encoder、无向量」） |
| C3 | 第六节「可量化的 project facts」数字与代码一致：源文件数、测试类数、**断言总数（原 215 + 本轮新增）**、eval 任务条数（30） |
| C4 | `README.md` 同步：模块结构里的边界说明句、测试断言总数、评测集规模 |
| C5 | 可用 `Grep` 自查：全仓库文档中不再出现「无 rerank」「无失败重试与重规划」「样例 4 条」等已过期表述（若对应能力本轮未做，则保留并加注日期） |

**涉及文件**
- `docs/RESUME.md`（第三节 / 第五节 / 第六节 / 第八节）
- `README.md`
- `.workbuddy/memory/MEMORY.md`（项目长期记忆同步「已知边界」）

---

### P1（本轮争取，时间紧可延后；不影响 P0 交付）

---

#### P1-1 · RAG lexical rerank 精排（BM25 top-N 确定性特征重排）

**需求描述**
RESUME 第七节把「BM25 + 向量混合召回 → cross-encoder rerank」列为工业标准，第八节又把「混合召回 + rerank」点名为**下一步**（让第七节从「论证」升级为「实证」）。
**零依赖下的诚实做法**：不碰向量、不碰模型，只对 BM25 召回的 top-N（N=20）做**确定性特征重排**，仅 3 个特征：
1. **查询词覆盖率**（命中查询词的种类比例）
2. **词项邻近度**（覆盖全部查询词的最小窗口跨度，越小越好）
3. **标题命中**（chunk 首行是 markdown `#` 标题且命中查询词 → 加权）

最终分 = `0.6 × 归一化BM25 + 0.4 × 特征分`，**确定性、可回归、不依赖模型**。

**用户故事**
> As a 用 `--rag` 检索本地文档的开发者, I want 召回结果按「相关且紧凑」而非仅「词频高」排序, so that 注入上下文的片段更精准，长文档不再靠堆词频挤掉短而准的段落。

**验收标准**
| # | 断言 |
|---|------|
| D1 | **覆盖率**：query 含 2 个词，chunkA 只含 1 个、chunkB 含 2 个 → 重排后 chunkB 排在 chunkA 之前（且特征分 B > A） |
| D2 | **邻近度**：两 chunk 均含全部查询词，一个两词相邻、一个相距 ≥ 20 词 → 相邻的排前 |
| D3 | **标题命中**：chunk 首行为 `# Redis` 且命中查询词 → 其重排分高于同内容但无标题的 chunk |
| D4 | **纯集合性**：重排只改变顺序，**候选集合与大小不变**；未命中任何查询词的 chunk 特征分恒为 0 |
| D5 | **可关闭**：rerank 开关关闭时行为与当前纯 BM25 **完全一致**（`RagTest` 现有 **7 条**断言零回归） |
| D6 | 端到端：`RagProvider.retrieve(query, k)` 返回串仍以 `## Retrieved context (RAG)` 开头，且 top1 与 rerank 后顺序一致 |

**涉及文件**
- `src/main/java/com/codeagent/rag/Reranker.java`（新增，唯一新增类）
- `src/main/java/com/codeagent/rag/CorpusIndexer.java`（`retrieve` 接入 rerank，默认开、可关）
- `src/main/java/com/codeagent/rag/RagProvider.java`（暴露开关）
- `src/test/java/com/codeagent/test/RagTest.java`（新增 D1–D6 断言）

---

#### P1-2 · MCP 真实子进程路径集成测试（验证已在简历上的 claim）

**需求描述**
RESUME bullet 16 声称「`McpLauncher` 用 `ProcessBuilder` 起子进程 + 独立 daemon 读线程规避 stdio 双向死锁」，但**只测过 `PipedStream` 内存桩，真实子进程从未跑过**——这是 C2 定义的「空头承诺」。
补一个**零依赖**的真子进程测试：在测试源码里加一个极简 JSON-RPC over stdio 回声服务器 fixture，用 `ProcessBuilder` 起 `java -cp <classpath> <fixture>`，走完整 `McpLauncher.launch` → `initialize` → `tools/list` → `tools/call`。

**用户故事**
> As a 要在面试讲「stdio 缓冲区死锁」这个坑的候选人, I want 真实子进程路径有一条自动化测试兜底, so that 我能说「这条链路有测试」，而不是「理论上能跑」。

**验收标准**
| # | 断言 |
|---|------|
| E1 | 通过 `McpLauncher.launch(...)` 起真实子进程，握手成功：`client.toolDefs()` **非空**且包含 fixture 声明的工具名 |
| E2 | `callTool(name, args, timeout)` 返回 fixture 的**预期文本**（断言正确值，不是"不报错"） |
| E3 | **无死锁**：连续发起 **≥ 50 次**请求全部返回（这是"独立读线程"这个卖点的直接验证） |
| E4 | `client.close()` 后子进程退出，测试进程不挂起（带超时保护） |
| E5 | 环境受限（沙箱禁止 `ProcessBuilder.start()`）时**打印 `SKIP` 而非 `FAIL`**，并在输出中明示跳过原因——不得让环境差异污染 `ALL TESTS PASSED` 的可信度 |

**涉及文件**
- `src/test/java/com/codeagent/test/McpTest.java`（新增真子进程用例）
- 新增 fixture（测试源码内的 main 类，如 `test/McpEchoServerFixture.java`），复用现有 `JsonRpcClient` 协议层
- `src/main/java/com/codeagent/mcp/McpLauncher.java`（**原则上不改**；若发现真 bug 才修，并同步记入 P0-3 文档）

---

### P2（候选，本轮不做，仅登记）

---

#### P2-1 · MCP 客户端补 `resources/list` + `resources/read`（只读）
**需求描述**：当前客户端只有 tools 能力。补**客户端侧**只读资源拉取（**不是**实现 MCP server 端）。
**为何是 P2**：对终端编码 Agent 的用户价值低（本地已有 `read_file`），且会扩大协议面，与 C1 冲突。
**若做的前提**：P1-2 已通过，且 RESUME 明确标注「客户端基础版，无 server 端、无 prompts/sampling」。
**涉及文件**：`mcp/McpClient.java`、`mcp/McpTest.java`

---

## 四、优先级总览

| ID | 需求 | 优先级 | 类型 | 预估新增/改动 |
|----|------|--------|------|--------------|
| P0-1 | 评测集扩到 30 条 | **P0** | 数据补全 | 1 数据文件 + 1 测试类 |
| P0-2 | Coordinator 失败重试 + 一次重规划 | **P0** | **修缺陷** | 1 主类 + 1 测试类 |
| P0-3 · 文档与简历口径同步 | **P0** | 诚信收口 | 3 文档 |
| P1-1 | RAG lexical rerank | P1 | 新增能力 | **1 新增类** + 2 主类 + 1 测试类 |
| P1-2 | MCP 真子进程测试 | P1 | 补验证 | 1 测试类 + 1 fixture |
| P2-1 | MCP 客户端 resources 只读 | P2 | 扩协议面 | 1 主类 + 1 测试类 |

**建议执行顺序**：P0-2 → P0-1 → P0-3 → P1-2 → P1-1（先修缺陷、再补数据、最后收口文档；P1 先补验证再补能力）。

---

## 五、明确不做清单（★ 本节是本 PRD 的核心交付）

| # | 不做 | 理由 | 简历替代口径（怎么讲） |
|---|------|------|----------------------|
| 1 | ❌ **F · 长期记忆自动写入**（让模型自动决定记什么） | **与本项目最亮眼的设计论点直接冲突**。RESUME 第三节明写防脏记忆三道闸第一条就是「**不写模型推断的东西**」。自动写入 = 把判断权交给模型，等于自毁这条论点，且引入脏记忆污染、误记密钥等不可逆风险。现有 `/memory` 手工命令 + 召回加权（BM25×重要性×时效性）已闭环。 | 「记忆写入刻意保持**人工显式**，因为 Agent 误记的代价远大于漏记——这是设计选择，不是没做」 |
| 2 | ❌ **C · MCP server 端 / resources(服务端) / prompts / sampling** | 本项目是**编码 Agent 客户端**，server 端的用途是「让别人来调我」，对本终端用户**价值为零**；且要实现 JSON-RPC 请求分发 + 三类能力，代码量大——正是 C1 定义的功能蔓延典型。 | 「**只做客户端**，server 端没做」——现状已诚实标注，不必补 |
| 3 | ❌ **B2 · Reflection 自我纠错（critic 子 Agent 循环）** | ① 需新增一个子 Agent 角色与循环，属**新增架构**而非修缺陷；② 效果强依赖模型质量，离线只能测编排骨架、测不出真实收益；③ 收益 < 成本，且易与 P0-2 的重规划职责重叠。 | 「失败重试与重规划已做；**Reflection 自我纠错未做**」——保留这条"没做"，比做一半更可信 |
| 4 | ❌ **向量召回 / embedding / HNSW / 混合召回 / cross-encoder rerank** | 零依赖铁律（C3）下**不可能自研 embedding**；若接外部 embedding 服务，则既破零依赖、又让「零依赖自研检索」这条 bullet 变注水（违反 C2）。 | 「BM25 是**针对标识符/报错串的字面匹配**刻意选型；语义检索才需要向量，规模化演进路径见第七节」 |
| 5 | ❌ **并行子 Agent / 线程池编排** | 单机 CLI 一问一答，**无并发需求**；引入并发会放大权限门与上下文治理的竞态面，且测试不可确定性复现。 | 「串行是刻意设计；何时该上 MQ / 并发见第七节」 |
| 6 | ❌ **Redis / MySQL / MQ / langfuse / OpenTelemetry 实际接入** | 违反 C3（第三方依赖）+ C2（注水）。本项目**刻意零中间件**是最高级谈资，落地反而毁掉它。 | 写「**中间件选型能力**」bullet：论证不同规模该引入什么、为什么，不引入时靠什么兜住 |
| 7 | ❌ **新增 `EvalTask` 断言字段**（`expectDeny` / `expectFileUnchanged` / 分类 tag 等）与**第二个评测集文件 / 评测分类体系** | C1 极简：本轮只补数据，不动 schema、不加文件。新字段会让评测框架膨胀且需同步改 `EvalReport`。 | 「30 条端到端任务，聚合成功率/工具准确率/平均步数/平均 token」 |
| 8 | ❌ **任何第三方依赖**（Maven/Gradle/launch 脚本改造、langchain / okhttp / jackson / 向量库 client / embedding SDK） | C3 铁律。 | 「`javac` 即可编译运行，0 个第三方依赖」 |
| 9 | ❌ **为抬高 mock / 真实模型成功率而放宽断言**（如把 `expectContains` 改成空、把 `maxSteps` 调大以规避 `MAX_STEPS`） | 评测集的价值在于**暴露**模型弱点，不在于刷分。M11 已记录：真实 GLM 25% 是模型弱点，非框架 bug。放宽断言 = 让评测失去意义，且一旦被发现即为简历注水。 | 「真实模型成功率 25% 是**基模 tool-use/终止行为**的真实弱点，正是 eval 该暴露的」 |

---

## 六、待确认问题

| # | 问题 | 影响 | PM 建议 |
|---|------|------|---------|
| Q1 | **P0-2 的「重规划」是重新调 planner 产出新计划（多一次 LLM 调用 + 延迟），还是跳过失败步骤直接综合（零额外调用）？** | 直接决定 P0-2 的实现规模与运行时成本 | 建议：**重试 1 次 → 仍失败则触发 1 次重规划**（默认开，可用 `maxReplans=0` 关闭）。理由：重规划是 RESUME 第五节点名的缺口，且 `maxReplans=1` 已把最坏延迟锁死。若你更看重延迟，改为"跳过失败步直接综合"也能去掉一个"没做"，但收益减半。 |
| Q2 | **P1-2 在受限沙箱里 `ProcessBuilder` 可能启动失败，是否接受「spawn 失败 → 打印 `SKIP` 而非 `FAIL`」？** | 与「测试铁律：必须断言正确值」存在张力——SKIP 意味着某些环境下这条 claim 实际未被验证 | 建议：**接受 SKIP，但必须在输出中显式打印 `SKIP` 及原因**，并在 RESUME 中只写「有自动化测试覆盖」而不夸大。若你要求零 SKIP，则该项降级为手工验证清单、不进自动化测试。 |
| Q3 | **P1-1 若做，RESUME 口径写「BM25 + lexical 特征 rerank（基础版，无向量 / 非 cross-encoder）」还是保守地继续标「无 rerank」？** | 影响简历 bullet 15 与第五节的措辞 | 建议：**写前者并明确标注边界**。做都做了，诚实标注边界比继续标"没做"更有信息量；关键是不能让人误以为是 cross-encoder 或混合召回。 |
| Q4 | **30 条评测任务是否需要覆盖 Phase-2 能力（memory / rag / skills / workflow）？** | 若需要，必须改 `EvalHarness` 去初始化这些可选能力——违反 C1 极简 | 建议：**不覆盖**。`EvalHarness` 当前只注册 6 个内置工具，Phase-2 能力各自已有单测（Retrieval/Memory/Skills/Rag/Mcp/Workflow）。评测集聚焦**主循环端到端行为**即可。 |

---

## 七、交付检查清单（M13 完成判定）

- [ ] `javac` 零依赖编译通过
- [ ] `AllTests` 输出 `ALL TESTS PASSED`，断言数 ≥ 215 且原 215 条零回归
- [ ] P0-1：evalset ≥ 30 条，A1–A7 全部满足
- [ ] P0-2：B1–B7 全部满足（含 B1 零回归）
- [ ] P0-3：C1–C5 全部满足，文档中无过期/不实表述
- [ ] P1（若做）：D1–D6 / E1–E5 全部满足
- [ ] 第五节「不做清单」9 项**无一被偷偷做进去**
