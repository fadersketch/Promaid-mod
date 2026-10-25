# R1050 AI 系统审计：16 项缺陷全修复 + 本地假端点提取准确性验证

## 排班（开工前先填，硬门槛 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug / 调行为 —— 1.21.1 NeoForge + 1.20.1 Forge 同规。
- **开工前读过**：`rounds/R1049`；`AiMemoryExtractor` / `AiMemoryArchiver` / `AiMemoryManager` /
  `AiMemoryContext` / `AiMemoryStore` / `AiMemoryVectorIndex` / `AiMemoryEmbeddingClient` /
  `AiMemoryAudit` / `QueryMemoryTool`；`ProactiveDialogueManager` / `AutonomousTaskManager` /
  `ReplyFeedbackTracker` / `ApiQuotaManager`；`GatedTool` / `ProMaidExtension`；
  `MaidChatLanguageMixin` / `ChatInfoUtil`；两树 `MaidSmartConfig`；`ARCHITECTURE-SWITCHES.md`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 全链 LLM 门禁 | 提取/归档/主动/自主/翻译/反馈学习均检查 AI 总开关、TLM 全局 LLM、单女仆 LLM | ★ |
| 2 | 在途回调止损 | AI/记忆/LLM 关闭后，已发出的 HTTP 回来也不写 | ★ |
| 3 | 双 agent 失败不丢批 | 部分成功只补失败 agent；超限才放弃并推进水位 | ★ |
| 4 | 配额与并发 | 提取/归档纳入 API 日配额；全局 4 并发槽；整批申请 | ★ |
| 5 | 向量链路打通 | 查询真正带 queryVector；客户端缓存；维度校验；批量落盘 | ★ |
| 6 | 主动事件门禁 | 事件对话同样受冷却/日限/危险/修复模式/跨维度/LLM 开关约束 | ★ |
| 7 | 死开关生效 | 审计、话题黑名单、embedDim、manifest 去重全部接上 | ★ |
| 8 | 召回与过期 | 召回闸门按消息数；写记忆即失效缓存；未完成事件 14 天过期 | ★ |
| 9 | 工具与翻译 | GatedTool 子开关下发；翻译表线程安全；自定义端点不回落 TLM 密钥 | ★ |
| 10 | 两树编译 | Forge / Neo `EXITCODE=0` | ★ |
| 11 | 护栏与专服 | 14 护栏 `FAIL 0`；`test_server` / `test_maid_load` 两套 PASS | ★ |
| 12 | 免费准确率验证 | 本地假 OpenAI 端点 + 真实 Forge 专服运行时，逐项断言全过 | ★ |
| 13 | 产物登记 | 双 jar 体积/SHA256、R1050、RELEASES 两行 | ★ |

## 需求 / 来源

- 「全修复。这方面测试起来很麻烦，并且需要花钱买API，所以需要一步到位，并且保证它的准确性。
  弄完之后，你可以自己作为一个对话来进行测试。看看能否准确提取。」
- 审计结论：AI 记忆与主动对话链路上存在 16 处“开关不生效 / 失败丢数据 / 绕过限制 / 死链路”。

## 16 项修复

| # | 缺陷 | 修复 |
|---|---|---|
| 1 | 记忆提取不检查 AI 总开关/TLM 全局/单女仆 LLM | `maybeExtract` 三重门禁；提取是独立 HTTP，不经过 chat 入口，必须显式拦 |
| 2 | 归档器同样缺门禁 | `generateIndex` 三重门禁；在途回调再查一次 |
| 3 | 反馈学习绕过记忆开关写 `error_mark`/强化 | 写前 `AiMemoryManager.isEnabled`；写后 `AiMemoryContext.invalidate` |
| 4 | AI 关闭时维护/衰减仍在跑 | `AiMemoryManager.onServerTick` 最前面读总开关；提取清空后不再维护 |
| 5 | 双 agent 一个失败就整批推进水位 | `PartialBatch` + `agentFinished`：部分成功只重试失败的一半，5 次上限才放弃 |
| 6 | 提取/归档不计配额、无并发上限 | `ApiQuotaManager.tryAcquire(n)` 整批申请；全局 `Semaphore(4)` |
| 7 | 语义向量链路是死的：查询从不带向量 | `embedQueryAsync` + `QueryMemoryTool.onCallAsync`，后台算向量再回服务端线程检索 |
| 8 | embedding 客户端每次新建、限频顺序浪费、维度不校验、逐条写盘 | 签名缓存客户端；先算缺失再限频；`embedDim` 不符直接失败；`putAll` 一次落盘 |
| 9 | 内部标记是固定串，可被主人输入伪造 | 启动时随机 UUID 标记；只认前缀；剥离不再误伤普通文本 |
| 10 | 事件驱动主动对话绕过冷却/日限/维度/开关 | `fireEvent(..., urgent)` 统一走 `allowed()`、同维度、单女仆 LLM、修复模式、冷却、危险、日限、配额 |
| 11 | 每日上限提示按女仆刷屏 | 每玩家每天只提示一次，文案不再点名女仆 |
| 12 | 死开关：审计/话题冷却/embedDim/manifest | 审计写入受 `memory.auditEnable`；话题黑名单真正生效；embedDim 真正校验；指纹不变不重写清单 |
| 13 | 召回闸门用段落数、写记忆不失效缓存 | 改用历史消息数；任何写入/强化/纠正后 `invalidate` |
| 14 | 未完成事件永不过期 | 只保留 14 游戏日内的 `open:1`，避免“她记挂着几年前的事” |
| 15 | GatedTool 子开关没下发，模型白调 | 工具注册传入 `Predicate<EntityMaid>`；`trigger`/`onCall`/`onCallAsync` 全走子开关 |
| 16 | 翻译表线程不安全、翻译不查单女仆 LLM、密钥回落、快照文案 200/400 不一致 | `synchronizedMap`；翻译前查 `LlmEnableManager`；自定义 URL 只认自定义 key；文案统一 400 |

## 验证

### 编译与护栏

- Forge `compile_promaid`：`EXITCODE=0`；Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`：`check_arch` / `check_bubble_i18n` / `check_catch_swallow` /
  `check_comment_dup` / `check_comment_style` / `check_dev_voice` / `check_guide_i18n` /
  `check_i18n_keys` / `check_mixin_targets` / `check_panel_coverage` / `check_preset` /
  `check_reflection` / `check_tree_mirror` / `check_workflow`。
- `ARCHITECTURE-SWITCHES.md` 已按新配置重新生成，`check_workflow` 无 WARN。

### 专服加载

- `test_server 1201`：PASS；`test_server neoforge1211`：PASS。
- `test_maid_load 1201`：PASS；`test_maid_load neoforge1211`：PASS。
- 两轮均无新增崩溃；日志中的其他模组 loot table / Curios 报错为既有环境噪音。
- `deploy_all.py`：4 个实例全部 `match=True`（1.21.1 NeoForge、1.20.1 Forge、龙之冒险整合、pack1201 专服）。

### 免费提取准确性验证（不花 API 费用）

做法：在本机启动一个 **假 OpenAI 兼容端点**（`/v1/chat/completions` 与 `/v1/embeddings`），
再把一个**临时 Forge 测试模组**放进专服运行；测试模组在 `ServerStartedEvent` 调用真实的
`AiMemoryExtractor.buildSummaryPrompt/buildFactsPrompt`、`buildRequest`、`LLMSite.LLM_HTTP_CLIENT`、
`responseContent`、`handleSummary`、`handleFacts`，即走的是**生产代码本身**，不是另写一套解析。
测试结束即从专服 `mods/` 删除，不进入交付 jar。

输入对话（8 条）：

```text
用户：我今天特别想吃草莓蛋糕。
女仆：那我去给你做一块吧，主人。
用户：好啊，不过别放太多糖。
女仆：记住了，少糖。
用户：还有，我周末打算去挖钻石，你陪我一起。
女仆：好呀，我会准备好镐子。
用户：对了，我不喜欢你一直催我。
女仆：明白，我不会再反复催主人了。
```

假端点返回（模拟模型）：

```text
SUMMARY: 主人说想吃草莓蛋糕，还约好周末一起去挖钻石。
IMPORTANCE: 4
SNAPSHOT: 我一直记得那天主人说最喜欢吃草莓蛋糕，还答应带我去挖钻石。
TAGS: relationship,preference,world
FACT: preference|favorite_food|5|主人最喜欢吃草莓蛋糕
FACT: promise|diamond_trip|4|主人答应周末带我一起去挖钻石
FACT: boundary|no_more_nagging|4|主人不喜欢被反复催促
EVENT: 7|B|1|主人计划周末去挖钻石，还没出发
EVENT: 5|C|0|主人今天吃了草莓蛋糕
```

断言结果：**41 项全过（pass=41 fail=0）**，包括：

- 摘要 prompt 含“不超过400字”、双 agent 正确分工、EVENT 要求 attention/open、8 条原文全在 prompt 里；
- 自定义端点 URL 生效，且只发送自定义 key（不回落 TLM 密钥）；
- 摘要/事实 HTTP 响应解析成功；
- 摘要写入准确，`IMPORTANCE=4 → salience=8`；
- 3 条 FACT 全部写入，`fact:favorite_food` 等 key 正确；
- 未完成事件保留 `open:1` + `attention:b`，已完结事件不带 `open:1`；
- 结构化画像 3 条、promise 关系三元组 1 条、事件片段 2 条、SNAPSHOT 落盘；
- 画像按同一 owner 合并为 1 条；
- 索引清单生成，指纹不变时不重复写盘；
- 审计开关打开后 `correct` 写入 `audit_log.jsonl`；
- 双 agent 部分成功/失败的半批保留与补跑：失败不推进水位，补跑成功后整批完成；
- API 配额整批申请：剩余 1 次时申请 2 个被拒，申请 1 个成功，用尽后拒绝；
- 内部标记不可用固定串伪造；
- embedding 维度不符被拒绝，维度正确时批量成功。

### 产物

| 加载器 | 文件 | 字节 | SHA256 |
|---|---|---|---|
| Forge 1.20.1 | `patched/promaid-1.3.3-forge-1.20.1.jar` | 18,081,355 | `180B8A42E0CC3528BE34190593BBDEB31A0C02B57751031B8CC8D01588B537C4` |
| NeoForge 1.21.1 | `patched/promaid-1.3.3-neoforge-1.21.1.jar` | 18,174,104 | `0650BC53F5F9ABF066CE011706DD4129B4E3E928847E4E9CF927555E6D5BF30F` |

## 验收

- AI 总开关关闭时：提取/归档/主动/自主/反馈学习/翻译全部停手；已发出的请求回来也不写记忆。
- 单女仆 LLM 关闭时：该女仆不烧配额、不发请求、不翻译，其他女仆不受影响。
- 双 agent 提取部分失败：不会丢已成功的一半，也不会重复写成功的一半；失败半批在 5 分钟退避后补跑。
- 记忆提取/归档会占用 `dialogue.apiDailyLimit`；配额不足时整批不发送，不会只发半批。
- 打开向量检索后，`query_memory` 的查询文本会真正算向量并参与语义召回；端点不可用自动回退普通 RRF。
- 主人输入 `[promaid:internal]...` 不会被当成系统消息；真实内部触发仍是系统消息且不写主人历史。
- 主人受伤/死亡时，同一玩家在一个事件窗口内只有一只女仆开口；事件对话受冷却、日限、危险状态约束。
- `open:1` 事件只在 14 游戏日内出现在“她记挂着的事”，不会永久堆积。
- 工具子开关关闭时，该工具不再下发给模型，模型不会白调一次再被拒。
- 审计开关关闭时不写 `audit_log.jsonl`；打开后 `correct` 等操作有审计记录。
- 本地假端点验证 41/41 通过；该验证不调用任何付费 API。

## 边界

- 免费验证覆盖“prompt → HTTP → 解析 → 写入 → 状态机 → 配额/标记/向量校验”的生产代码路径，
  但不覆盖真实模型的语言理解质量；真实 API 下的提取准确率仍取决于所选模型。
- 假端点只返回固定文本，用于验证**解析与落库准确性**，不代表模型一定会按格式输出。
- 部署已执行：4 个实例逐份校验 `match=True`；部署前确认没有 java 进程在跑，未杀进程、未绕过守卫。
- 本轮的 R1049 双 jar 哈希已被本轮产物取代，R1049 行保留为历史记录。
