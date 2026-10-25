# R1049 Promaid AI 总开关 + 环境优先主动对话 + 记忆长度与召回链路修复（两树同步）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug / 调行为（1.21.1 NeoForge + 1.20.1 Forge 同步；AI 总闸、主动对话触发与记忆召回）。
- **开工前读过**：`rounds/R1048`、两树 `MaidSmartConfig` / `PromaidConfigScreen` / `MaidConfigPreset`、
  `ProactiveDialogueManager` / `ProactiveStage` / `AutonomousTaskManager` / `ChatInfoUtil`、
  `AiMemoryManager` / `AiMemoryContext` / `AiMemorySearch` / `AiMemoryRecallGate` / `QueryMemoryTool` /
  `AiMemoryModels` / `AiMemorySnapshotStore`、`MaidChatLanguageMixin` / `PromaidPromptMixin`、
  `ARCHITECTURE-SWITCHES.md`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 审计全部 Promaid AI/LLM 入口 | 主动对话、自主决策、记忆提取/注入/检索、AI 工具、语言翻译、提示词追加逐一列清 | ☑ |
| 1 | 真正 AI 总开关 | 新增 `ai.enable`（默认开），覆盖本模组自己的全部 AI 功能；TLM 自带 AI / TTS / 其他模组不受影响 | ☑ |
| 2 | 保留工具子开关 | `aitools.enable` 继续作为 AI 工具子开关，`GatedTool` 同时检查两级 | ☑ |
| 3 | 主动对话环境优先 | 4 阶段；环境简报来自维度/时间/天气/主人血量与手持物/距离/女仆任务/附近威胁 | ☑ |
| 4 | 记忆只作可选补充 | 用环境关键词走 `searchEnvironment`（不含“最近段落”路），无相关命中就不塞记忆 | ☑ |
| 5 | 内部触发不再冒充主人 | `[promaid:internal]` 标记；混入按 system 消息发送且跳过 `addUserHistory` | ☑ |
| 6 | 删除脚本化催促 | 移除 TOPIC_PUSH / RELATION_CANDIDATE / FINAL_NOTICE / LONG_SILENCE_CHECK 与 `dialogue.longSilenceMax` | ☑ |
| 7 | 记忆长度放宽 | 段落 300→600、消息 200→600、投影 600→1200、TopN 3→5、条目上限 64→128，以及关系/画像/快照/索引同步放宽 | ☑ |
| 8 | 召回链路修复 | RRF 按内容融合；空白查询不再算“话题变化”；时间路用当前游戏时间；自动投影按上一轮主人发言检索 | ☑ |
| 9 | 面板 / 译文 / 预设 / 覆盖清单 | 新总闸行、删除长沉默行、4 份 lang、`panel_coverage.txt`、`ARCHITECTURE-SWITCHES.md` 同步 | ☑ |
| 10 | 两树编译 | Forge / Neo 均 `EXITCODE=0`、0 error | ☑ |
| 11 | 护栏与专服回归 | 14 护栏 `FAIL 0`；`test_server` / `test_maid_load` 两套均 PASS | ☑ |
| 12 | 部署与登记 | 4 个实例逐处 `match=True`；RELEASES 追加正式 1.3.3 两行 | ☑ |

## 需求 / 来源

- 「首先有关AI功能，必须要有一个真正的总开关。然后是把主动对话进行更改。尽可能是贴合当前的环境而不单单的是根据记忆。并且记忆的长度太短了。召回记忆的链路真的没问题吗？」
- 此前确认的作用域：AI 总开关只影响本模组自己的 AI 功能，不影响 TLM 自带 AI / TTS / 其他模组；关闭时静默，不刷聊天栏。

## AI 总开关：`ai.enable`

| 面板行 | TOML 键 | 默认 | 作用 |
|---|---|---|---|
| Promaid AI 总开关 | `ai.enable` | 开 | 本模组自己的主动对话、自主决策、AI 记忆提取/注入/检索、AI 工具、语言翻译、提示词追加 |
| Promaid AI 工具总开关 | `aitools.enable` | 开 | AI 工具子开关；与总闸同时为开才放行 21 个工具 |

门控点：

- `ProactiveDialogueManager` / `AutonomousTaskManager` 的 tick 与全部事件处理器。
- `AiMemoryManager.isEnabled(...)` 最前面读总闸 → 记忆上下文、提取、归档、向量、工具、好感/喂食/死亡写入一并停手。
- `GatedTool` 的 `onCall` / `onCallAsync` / `trigger`。
- `MaidChatLanguageMixin` 的语言强制与内嵌翻译；`PromaidPromptMixin` 的坐姿工作说明与输出语言硬指令。
- `WorkListContext`（工作清单注入）；`AffectContext` / `AiMemoryContext` 经 `isEnabled` 覆盖。

不动的部分：`MaidChatLlmGateMixin`（per-maid LLM 开关）、TLM 自己的对话、TTS、其他模组工具。

## 主动对话：环境优先，4 阶段

`LIGHT_FOLLOWUP(120s) → WORLD_OBSERVE(300s) → QUIET_MOMENT(1200s) → IDLE`。

- **环境简报**：维度、时间阶段、天气、主人距离/血量/手持物、女仆任务/手持物/血量、12 格内威胁数量与最近距离。
- **开口条件**：`WORLD_OBSERVE` 只在有值得注意的事时开口（雷雨/夜色/敌人/主人受伤/正在干活/手持特定物品）；
  `QUIET_MOMENT` 是安静陪伴的一句轻话；没有内容就顺延阶段，不烧配额、不记冷却。
- **记忆**：只把环境关键词作为查询走 `AiMemorySearch.searchEnvironment`（排除“最近段落”路），
  命中才在提示词里加一句“你隐约想起…”，并明确要求不相关就忽略。
- **不再脚本化**：删除“主人还在吗”“不打扰你了”“主人好一阵子没说话”这类固定催促阶段。
- **内部触发**：`ChatInfoUtil.INTERNAL_MARKER = [promaid:internal]`；`MaidChatLanguageMixin` 在
  `normalChat` 里把带标记的提示改成 `LLMMessage.systemChat`，并跳过 `addUserHistory`——
  模型不会再把女仆自己的触发当成主人刚说的话，也不会污染后续对话历史。
- 主人互动仍然重置周期；冷却、日上限、全局 API 配额、敌袭冻结、修复模式（话多/被纠正）全部保留。

## 记忆长度

| 项 | 旧默认 | 新默认 |
|---|---|---|
| `memory.maxEntries` | 64 | 128 |
| `memory.promptTopN` | 3 | 5 |
| `memory.maxMessageChars` | 200 | 600（范围 50–2000） |
| `memory.projectionChars` | 600 | 1200（范围 200–4000） |
| 段落存储上限 | 300 | 600 |
| 关系 object | 120 | 240 |
| 事件摘要 / 画像 / 画像条目 | 200 / 400 / 200 | 400 / 800 / 400 |
| 记忆快照 | 200 | 400 |
| 索引检索片段 / 日记注入 | 120 / 150 | 240 / 300 |

## 召回链路修复

1. **RRF 融合键**：原 `prefix + "|" + content` 会把 BM25 与最近路命中的同一段拆成两条；
   现在按 `content` 融合，同一段被多路召回时分数累加；最近路段也限制为最近 12 条，避免
   “只要新就全量参与融合”把无关记忆顶上来的噪声。
2. **召回闸门**：空白查询不再被当成“话题变化”；只有非空且签名不同才算换话题。
3. **时间路**：`AiMemorySearch.search(..., nowTick)` 传入当前 `level.getGameTime()`，
   “昨天/上周/N 天前”按此刻解析，不再按最新一条记忆的时间解析。
4. **自动投影**：`AiMemoryContext` 读取历史里最近一条 `Role.USER` 消息（去掉 TLM 的 `<context>` 段），
   用 `searchEnvironment` 做相关段落召回；检索不到才回落到重要度排序。召回闸门也用这条查询。
5. **`QueryMemoryTool`**：传入当前游戏时间，时间路与对话内检索同口径。

## 验证

- 两树编译：Forge / Neo 均 `EXITCODE=0`、0 error。
- 14 护栏：`FAIL 0`（`check_arch` 产物一致，`check_mixin_targets` 283 个注入点校验通过）。
- 专服：`test_server` 1201 / neoforge1211 均 PASS；`test_maid_load` 两套均 PASS。
  - Forge 侧首次 maid load 暴露 1.20.1 Mixin 0.8.5 对 `@Redirect` 静态处理器的要求：
    `normalChat` 是实例方法，处理器需为实例方法；NeoForge 侧保持静态处理器。修正后重测 PASS。
- 部署：4 个实例逐处 `match=True`。
- 老档迁移：新增内部一次性标记 `memoryLengthMigrated`（`promaid-internal.toml`），首启只把
  **仍停在旧默认** 的四项搬高（64→128、3→5、200→600、600→1200）；玩家自己调过的值不动。
- 产物：Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,072,673 字节
  SHA256 `76C9109FFC6F9443ADC0A9E687AA82EB83F05041E46F21AB902492197E812979`；
  Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,165,392 字节
  SHA256 `358F279F51DC887D7D0D6666ABF4B686F81CE4BF55DDDFBC8B96126A20B04881`。

## 验收

- 设置页「AI 工具」页第一行出现「Promaid AI 总开关（默认开）」；关闭后主动对话、自主决策、记忆开关状态、
  AI 工具、内嵌翻译全部停手，TLM 自己聊天仍可用。
- 主动对话只在该说话时开口：先看环境，记忆仅在环境关键词命中时作为可选补充；不再出现“主人还在吗”这类固定催促。
- 记忆投影变长（默认 1200 字、TopN 5），`query_memory` 检索到的时间问题按当前游戏日解析。
- 老档首启：日志出现「配置迁移 / 记忆长度默认值上调」；面板显示 128 / 5 / 600 / 1200；手调过的项保持原值。
- 主动对话触发在对话历史中显示为系统消息而不是主人消息（日志/历史可核对）。

## 边界

- 总开关只覆盖本模组自己的 AI；TLM 自带 AI 对话、TTS、其他模组工具不在作用域内。
- `aitools.enable` 仍是子开关：总闸开、工具子闸关时只停工具。
- 主动对话仍是“到点才检查”，不保证每次扫描都开口；敌袭冻结、日上限、全局 API 配额继续生效。
- 记忆内容长度放宽只影响新写入/新投影；旧记忆保持原文字，不重写、不重新提取。
- 召回质量依赖 BM25 中文分词；向量语义路仍需自行配置 embeddings 端点，默认关闭。
