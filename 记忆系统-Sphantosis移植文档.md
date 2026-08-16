# Promaid 记忆系统 —— Sphantosis 移植文档(v1.5.378)

> 分支:`experimental/memory-port`(基于 maidmods 工作树 v1.5.377)
> 移植来源:Sphantosis `experimental/advanced-features-experiment` 分支
> 对应源文件:`src/python/cognitive/composite/skiplist_index.py`、`src/python/computing_engines/memory_archiver.py`、`src/python/config/memory_settings.py`、`src/python/computing_engines/recaller/workflows.py`、`src/python/modules/role_predictor/prompts/memory_index_generator.txt`
> 部署:随 `Promaid-1.0.12.jar`(备份 `.bak_v1377`)

---

## 1. 一句话总览

女仆的记忆从"一堆段落全文扫描"升级为**带时间索引、带多级日记摘要、睡觉时自动整理**的分层记忆系统:平时对话照常写入段落;跨越游戏日/周/月边界(或主人睡醒)时,后台自动把近期事件请 LLM 写成**第一人称日记**(日/3日/周/月四级压缩),连同关键事件引用永久归档;超过 3 个游戏日的短期记忆按"关联簇"整簇转为长期(不再随时间遗忘);对话检索时日记作为第五路召回直接命中整段时间线,LLM 还可用 `query_memory_index` 工具按时间范围主动翻日记。

## 2. 全景数据流

```
                       ┌─────────────────────────────────────────────────┐
                       │                    写入侧(原有)                   │
                       ├─────────────────────────────────────────────────┤
  对话/事件源 ──► AiMemoryExtractor(LLM 提取) ──► AiMemoryStore.addParagraph
  喂食/好感/死亡 ──► AiMemoryManager 事件源 ──┘        │
                                                        │ 写入时同步:
                                                        ▼
                                              AiMemorySkipList 时间索引
                                              (gameTick → 段落hash, O(log n))

                       ┌─────────────────────────────────────────────────┐
                       │              自动归档侧(本次移植新增)              │
                       ├─────────────────────────────────────────────────┤
  触发点A: AiMemoryManager.onServerTick(每 scanInterval 秒,扫描玩家
           周围128格女仆) ──► AiMemoryArchiver.tick(maid, level, false)
  触发点B: 玩家睡醒 PlayerWakeUpEvent(周围128格女仆)
           ──► AiMemoryArchiver.tick(maid, level, forceDayIndex=true)
                        │
                        ├─► 边界检测(archiver_state.json: 上次日/周/月)
                        │     跨日 → 昨日「日」索引 + 滚动「3日」索引
                        │     跨周 → 上周「周」索引(7游戏日)
                        │     跨月 → 上月「月」索引(30游戏日)
                        │     睡醒 → 当日「日」索引(部分天)
                        │
                        ├─► generateIndex: 跳表范围取事件段落 → 组 prompt
                        │     → 异步 LLM(sendAsync,60s超时,单飞守卫)
                        │     → 严格JSON{日记,事件节点} → memory_index.jsonl
                        │     失败 ─► pending 队列(持久化,下个tick重试)
                        │
                        └─► runTransfer: short_context 层段落
                              ngram-Jaccard≥0.42 并查集聚簇
                              簇内全部段落年龄 ≥ shortTermDays 游戏日
                              ──► 整簇打 long_term 标记(豁免衰减遗忘)

                       ┌─────────────────────────────────────────────────┐
                       │              读取侧(检索/注入/工具)                │
                       ├─────────────────────────────────────────────────┤
  AiMemorySearch 五路召回: 段落/最近/画像/关系 + [新]索引日记路 ── RRF 融合
  AiMemoryContext 投影注入: 核心记忆/相关记忆/今日回顾 + [新]记忆日记段
  QueryMemoryIndexTool(新LLM工具): 先列跨度 → 按范围取日记+关键事件原文
```

## 3. 新增组件明细

### 3.1 AiMemorySkipList —— 跳表时间索引

`promaid_src/com/maidsmart/memory/AiMemorySkipList.java`(移植 `skiplist_index.py`)

- key = 游戏 tick(段落 `eventTimeStart`),value = 段落 hash;同 tick 可重复
- O(log n) 插入与范围查询(`queryRange(start,end)`)、`queryBefore(ts,limit)`、`rebuild(entries)`
- **纯内存**,不持久化:数据源是 `paragraphs.jsonl`,`AiMemoryStore.load()` 末尾整体重建;`addParagraph()` 新段落时同步插入
- prune 删除段落后跳表会留 stale 项,查询侧经 `paragraphByHash()` 判空过滤(已注释说明)
- 用途:归档器收集"某时间跨度内的事件"不再线性扫全表;后续按时间的记忆检索同受益

### 3.2 AiMemoryIndexStore —— 多级记忆索引库

`promaid_src/com/maidsmart/memory/AiMemoryIndexStore.java`(移植 `memory_index_db` + `MemoryIndexRecord`)

- 四级常量:`日` / `3日` / `周` / `月`
- 记录结构( Gson jsonl 单行,UTF-8):

```json
{"level":"日","startTick":4560000,"endTick":4584000,"startDay":190,"endDay":191,
 "content":"今天主人带我去……(LLM第一人称日记)","eventIds":["1a2b3c:42","9f8e7d:17"],
 "createdAt":1755360000000}
```

- 落盘位置:`<存储目录>/memory_index.jsonl`(与 paragraphs.jsonl 等同目录,跟随灵魂绑定路由)
- 持久化时机:由 `AiMemoryStore.save()` 统一调度(跟随原有防抖批量写盘/flushAll 机制),归档成功时 `saveNow()` 强制立即落盘
- 幂等去重:`has(level,startTick,endTick)` 允许 ±1200 tick(≈1 游戏分钟)容差,对应 Sphantosis 的 60 秒容差
- 查询 API:`byLevel(level)`(按开始时间升序)、`findRange(level,startDay,endDay)`(区间相交即命中)、`all()`、`clear()`

### 3.3 AiMemoryArchiver —— 记忆自动归档器(核心)

`promaid_src/com/maidsmart/memory/AiMemoryArchiver.java`(移植 `memory_archiver.py`,全静态方法)

#### 3.3.1 调度入口 `tick(maid, level, forceDayIndex)`

时间基准 = `level.getGameTime()`(与既有每日巩固同源);`day = gameTime/24000`,`week = day/7`,`month = day/30`。

状态持久化在 `<存储目录>/archiver_state.json`:

```json
{"lastDay":191,"lastWeek":27,"lastMonth":6,
 "pending":[{"level":"日","startTick":4560000,"endTick":4584000}]}
```

流程(全部在服务端线程执行,轻量比较常驻,重活异步):

1. **首次对齐**:状态 `lastDay<0` 时只写入当前边界,不追溯历史跨度(对齐 Sphantosis 行为,防启动时批量补生成)
2. **跨日**(`day != lastDay`):
   - 昨日「日」索引:区间 `[(day-1)*24000, day*24000)`
   - 滚动「3日」索引:区间 `[(day-2)*24000, (day+1)*24000)`(按日对齐保证幂等)
3. **跨周**:上一周「周」索引 `[(week*7-7)*24000, week*7*24000)`
4. **跨月**:上一月「月」索引 `[(month*30-30)*24000, month*30*24000)`
5. **睡醒强制**(`forceDayIndex=true`):当日「日」索引 `[day*24000, gameTime)`(部分天,收尾归档点)
6. **重试**:遍历 pending,逐条再尝试;仍失败的留在队列
7. **状态落盘**:有变化才写
8. **短期→长期转移** `runTransfer(store, gameTime)`

#### 3.3.2 索引生成 `generateIndex(maid, level, lv, startTick, endTick)`

返回 true = 本轮已处理(成功/已存在/无事件跳过/已异步发出),false = 失败应挂重试。

1. 开关 `memory.indexEnable` 关闭 → false
2. `store.index().has(...)` 已有 → true(幂等)
3. **收集事件段落** `collectEventBlock`:跳表 `queryRange` 取 hash → `paragraphByHash` 还原段落,过滤条件:
   - 排除 `sourceType=summary`(摘要段落本身是压缩产物,不二次索引)
   - 排除 tags 含 `daily`(旧版规则每日回顾)
   - 排除 deleted / error_mark(error_mark/error_affected 被主人否定的记忆)
   - **月级**:按 salience 降序只保留 `memory.indexMonthTopN`(默认20)条,再恢复时间升序
   - 输出格式(喂给 LLM 的事件块,移植 Sphantosis 格式):
     `[第190天|event] 主人送了我一把铁剑(重要度k=7)【事件节点id:1a2b3c:42】`
   - 跨度内无事件 → **跳过视为成功**(不生成空索引,对齐原设计)
4. **LLM 站点**:与提取器同款——`maid.getAiChatManager().getLLMSite()`(仅 OpenAI 兼容站点),`memory.apiUrl/apiKey/apiModel` 任一填写则覆盖对应项;无站点/无模型 → false 挂重试
5. **单飞守卫**:`ARCHIVING` Map(女仆UUID→开始毫秒),进行中直接返回;超时(复用 `memory.extractTimeoutMin`,默认5分钟)视为卡死允许重试
6. **异步请求** `sendIndexRequest`:与 `AiMemoryExtractor` 完全同款——`ChatCompletion.create().model(model).userChat(prompt)`,`hasThinkingField` 时 `disableThinking`(省钱),`LLMSite.LLM_HTTP_CLIENT.sendAsync`,60 秒超时,回调 `server.execute(...)` 切回服务端线程。**不经 TLM 回调,不烧玩家配额、不污染聊天历史、无气泡/TTS 副作用**
7. **Prompt**(移植 `memory_index_generator.txt`,时间轴改为游戏日):要求第一人称日记、压缩程度随级别递增(日=详细 / 3日=中等 / 周=较压缩 / 月=只留最重要)、引用事件必须标注其 hash、禁止编造、严格 JSON 输出:

```json
{"日记":"……","事件节点":[{"id":"1a2b3c:42","名称":"主人送铁剑"}]}
```

8. **响应解析** `handleIndexResponse`:容忍 ```json 围栏;`日记` 为空 → 挂 pending;否则 `store.index().add(...)` + `store.saveNow()` 立即落盘,INFO 日志:
   `AiMemoryArchiver: 已归档日索引 第190天~第191天(关键事件3个)`
9. **失败不静默**:请求失败/空响应/解析异常 → `addPending` 把该边界写入持久队列,日志记录状态码/异常;下个 tick 自动重试

#### 3.3.3 短期→长期簇转移 `runTransfer(store, gameTime)`

promaid 段落之间没有图边,用**内容相似度聚类**近似 Sphantosis 的"连通分量":

1. 候选:`layer=short_context` 的段落(写入策略 `AiMemoryWriteStrategy` 的默认短期层),排除已打 `long_term`、排除 error 标记
2. 聚簇:bigram 集合 Jaccard ≥ 0.42 连边,并查集求连通簇(阈值与既有 error_mark 传播一致)
3. **整簇转移条件:簇内每一段的 `gameTime - eventTimeEnd` 都 ≥ `memory.shortTermDays`(默认3)游戏日** —— 任何一段还"新"就整簇等待,不拆散相互关联的事件簇(对齐原设计"存在任何节点少于该跨度则不转移")
4. 转移动作 = 打 `long_term` 标签(`markLongTermByHash`,严格按 `,long_term,` 全词匹配避免误伤)+ `saveNow()`
5. `long_term` 的实际效果:**prune 衰减遗忘豁免**(`AiMemoryStore.prune` 第2步增加 `!tags.contains("long_term")` 条件)——已判定为长期记忆的段落不再"30天未访问且低重要度"被删除;检索/注入行为不变(它们本来就在池子里)
6. 日志:`AiMemoryArchiver: 转移 N 个关联簇(M 段落)到长期记忆`

### 3.4 睡一觉自动处理(触发点)

`AiMemoryManager.onPlayerWakeUp(PlayerWakeUpEvent)`:

```
Sphantosis 原链路:  start_role_sleep(≥阈值时长) → 60秒后 wrap-up 收尾
                    → deactivate + 调度记忆分析 + archiver.tick(force_day_index=True)
Promaid 对应:      玩家睡醒(PlayerWakeUpEvent,睡过夜=新一天开始=天然收尾点)
                    → 周围128格内、已驯服、记忆开启的女仆
                    → AiMemoryArchiver.tick(maid, level, forceDayIndex=true)
                    = 生成刚结束这一天的「日」级日记 + 短期→长期簇转移
```

- 双开关:`memory.indexEnable` 且 `memory.indexOnSleep` 都开才触发
- 事件在服务端判断(`event.getEntity() instanceof ServerPlayer`),天然只在服务端生效
- 周期调度(触发点A)兜底:玩家不睡觉、用指令跳夜晚、挂机过夜——跨日边界同样会在下一个扫描周期(默认20秒)被检测到,只是少了"当日部分天"的强制归档(该索引会在次日跨日时以完整一天补上)

### 3.5 检索侧接入

#### 五路召回(AiMemorySearch)

`search()` 在原四路(段落ngram / 最近 / 画像 / 关系)之后新增第五路 `searchIndex`:

- query 的 uni+bi-gram 与**每条索引日记内容**做 ngram 命中;命中数×2.0 计分
- 命中输出形如:`【日记 第190~191天】今天主人带我去……`(截断120字)
- 语义:日记是压缩摘要,**命中即代表整段时间线的记忆相关**;RRF(k=60)自然融合,与既有四路互不干扰
- 开关关闭时此路直接返回空,零开销

#### 投影注入(AiMemoryContext)

在"今日回顾"之后新增 **记忆日记** 段:最近 2 条「日」级索引(按 endTick 降序),每条截断150字,格式 `【第190~191天】日记内容`。仍受整体 `memory.projectionChars` 上限与截断逻辑约束(核心记忆优先不受影响)。规则版"今日回顾"保留——LLM 日记生成之前的过渡期/关闭索引时仍有兜底。

### 3.6 query_memory_index 工具(移植 Sphantosis 同名工作流)

`promaid_src/com/maidsmart/memory/QueryMemoryIndexTool.java`,注册于 `ProMaidExtension`(工具id `query_memory_index`)

参数(schema 用 TLM 的 StringParameter/IntegerParameter):

| 参数 | 必填 | 说明 |
|---|---|---|
| `level` | 是 | `日` / `3日` / `周` / `月`,其他值直接报错提示 |
| `start_day` | 否 | 游戏日序号(世界创建起算);省略=该端不设限 |
| `end_day` | 否 | 同上 |

两段式用法(即 Sphantosis 的"前缀和式范围确定"):

1. **不带范围调用** → 返回该级别全部可用跨度列表 + 当前游戏日,如:
   ```
   【日级索引可用跨度列表(共5条)】(当前是第191天)
   - 第186~187天(关键事件 4 个)
   - 第187~188天(关键事件 2 个)
   ...
   请从中选择跨度后,以 start_day/end_day 二次调用本工具。
   ```
2. **带范围二次调用** → 区间相交的每条索引返回:日记全文 + 关键事件**原文**(eventIds 的 hash → `paragraphByHash` 还原,最多10条、每条截断80字、带重要度前缀)

防御:记忆总开关/per-maid 开关关闭、索引功能关闭、非服务端、非法级别均有明确文案返回。

## 4. 配置项(config/promaid-common.toml → [memory])

| 键 | 默认 | 范围 | 说明 |
|---|---|---|---|
| `indexEnable` | true | bool | 多级记忆索引总开关(生成/检索路/工具/投影段全部联动) |
| `indexOnSleep` | true | bool | 睡一觉自动处理(仅控制睡醒强制归档;周期边界归档不受此键影响) |
| `indexMonthTopN` | 20 | 5~100 | 月级索引按重要度保留的最大事件数 |
| `shortTermDays` | 3 | 1~30 | 短期→长期转移阈值(游戏日) |

既有键的联动关系:

| 键 | 与新系统的关系 |
|---|---|
| `apiUrl` / `apiKey` / `apiModel` | 索引生成 LLM 与提取器共用同一覆盖逻辑:留空跟随 TLM 女仆站点 |
| `extractTimeoutMin`(默认5) | 索引生成单飞守卫的超时复用此值 |
| `scanInterval`(默认20秒) | 周期归档 tick 的调用频率 |
| `projectionChars` | 投影总长上限(记忆日记段在其中) |
| `rrfK`(默认60) | 五路融合参数 |

语言键(zh_cn.json):`config.promaid.memory.indexEnable` / `indexOnSleep` / `indexMonthTopN` / `shortTermDays`。

## 5. 数据文件布局

每个女仆一份(灵魂绑定时跟随 SoulBindingService 路由到全局灵魂目录,跨存档共享):

```
<世界>/promaid_memory/<女仆UUID>/          (或灵魂目录)
├── paragraphs.jsonl        # 段落(原有;跳表数据源)
├── entities/relations/episodes/profiles.jsonl   # (原有)
├── meta.json               # (原有)
├── memory_index.jsonl      # ★ 新:多级记忆索引(永久归档)
├── archiver_state.json     # ★ 新:归档器边界状态+pending重试队列
└── working_note.txt / affect.json / persona.*  # (原有)
```

清空记忆(`clearAll`)会同步清空索引库、删除归档状态文件、重置跳表。

## 6. Sphantosis → Promaid 概念映射

| Sphantosis(Python) | Promaid(Java) | 适配点 |
|---|---|---|
| `SkipListIndex`(剧情秒→节点id) | `AiMemorySkipList`(游戏tick→段落hash) | 时间轴换为 MC 游戏 tick |
| `memory_index_db` + `MemoryIndexRecord` | `AiMemoryIndexStore`(`memory_index.jsonl`) | PersistentDatabase → jsonl,新增 startDay/endDay 便于 LLM 按天查询 |
| `MemoryArchiver.tick(now_story, force_day_index)` | `AiMemoryArchiver.tick(maid, level, forceDayIndex)` | 剧情时间→游戏时间;周=7游戏日、月=30游戏日 |
| `_llm_summarize`(VllmBackend+模板) | `sendIndexRequest`(直连 HTTP,同提取器) | 不经 TLM 回调,无 token/历史/气泡副作用 |
| `StoryClock.day_id/week_id/month_id` | `gameTime/24000`、`day/7`、`day/30` | — |
| 图连通分量整簇转移 | ngram-Jaccard≥0.42 并查集聚簇 | promaid 段落无边,以内容相似度近似 |
| 转移=移入 episodic_core | 转移=打 `long_term` 标签+衰减豁免 | promaid 单存储,用标签分层 |
| `query_memory_index` workflow | `QueryMemoryIndexTool` | 中文时间格式→游戏日序号(对 LLM 更友好) |
| `start_role_sleep→wrap-up(60s)→tick(force)` | `PlayerWakeUpEvent→tick(force)` | MC 睡醒即收尾,无需延时 |
| 角色后台线程每3秒 tick | `onServerTick` 每 scanInterval 秒(只扫玩家周围女仆) | 避免未加载/远离女仆空转 |
| `memory_settings.py` 白名单+memory_config.json | Forge config `memory.*` 四键 | 对齐模组既有配置体系 |
| `_pending_indexes` 内存队列 | `archiver_state.json` pending(持久化) | 重启后仍会补生成 |

## 7. 故障处理与边界行为

- **LLM 失败**:状态码/异常写日志,边界入持久 pending 队列,下个 tick 重试;成功后自动出队(幂等由 `has()` 保证)
- **异步竞态**:单飞守卫(每女仆同时最多一个在途请求)+ 超时放行;回调统一 `server.execute` 切回服务端线程写库
- **无站点/未配置**:不生成、不报错刷屏(挂 pending 等 TLM 配好站点后自动补)
- **跨度无事件**:视为成功跳过,不产生空日记
- **回滚兼容**:旧存档无 memory_index.jsonl / archiver_state.json → 首次 tick 只对齐边界,不回溯补生成历史;`config` 缺省全部开启
- **清空记忆**:索引/状态/跳表一并重置

## 8. 构建 / 部署 / 回滚

```bat
:: 仓库路径(C:\Users\Sketch\.zcode\workspace\default\promaid-mod,分支 experimental/memory-port)
git status                        :: 确认在实验分支
python gen_compile.py             :: 生成 compile_promaid.txt(需 maidmods\compile_addon.txt,已复制到仓库)
compile_promaid.bat               :: javac 编译到 out_promaid
python build_promaid.py           :: 打包(jar 名 promaid-1.0.0.jar,验证必需类与 mixin 类齐全)

:: 或走 maidmods 线(与部署 jar 完全一致)
cd C:\Users\Sketch\.zcode\workspace\default\maidmods
python gen_compile.py && compile_promaid.bat && python build_promaid.py
:: 产物 patched\promaid-1.0.12.jar(构建脚本自校验 out/jar/src 类数一致)

:: 部署(备份旧 jar 后覆盖)
copy /y "D:\...\mods\Promaid-1.0.12.jar" "D:\...\mods\Promaid-1.0.12.jar.bak_v1378"
copy /y patched\promaid-1.0.12.jar "D:\.minecraft\versions\1.20.1-Forge_47.4.21\mods\Promaid-1.0.12.jar"

:: 回滚
copy /y "...\Promaid-1.0.12.jar.bak_v1377" "...\Promaid-1.0.12.jar"
```

注:两棵树内容一致(仓库 experimental/memory-port ↔ maidmods\promaid_src);改代码时改一边后用 `robocopy /MIR` 同步另一边,避免再次分叉。

## 9. 游戏内验证清单

1. **睡觉归档**:床睡到天亮 → `latest.log` 搜 `AiMemoryArchiver`:
   - `已归档日索引 第X~Y天(关键事件N个)` ✅
   - 若当日无新记忆则无此行(正常);失败会有 `索引生成失败 ... (err=..,status=..)` 与后续重试
2. **数据落盘**:看 `<世界>/promaid_memory/<UUID>/memory_index.jsonl` 是否出现记录;`archiver_state.json` 的 lastDay 应等于当前游戏日
3. **对话检索**:问女仆"你还记得前几天我们做了什么吗"——观察是否触发 `query_memory_index`(TLM 调试面板/日志),或回答中复述日记内容
4. **投影注入**:有日记后,女仆对话上下文应含"记忆日记:【第X~Y天】…"(开 TLM 调试查看 system 注入)
5. **长期转移**:游戏内过 3 天以上(`shortTermDays`),`paragraphs.jsonl` 中旧 short_context 段落 tags 出现 `,long_term`;日志 `转移 N 个关联簇`
6. **开关**:config 关 `indexOnSleep` → 睡觉不再强制归档;关 `indexEnable` → 工具返回未开启、检索无日记路、投影无记忆日记段

## 10. 相关提交

| 仓库/分支 | 提交 | 内容 |
|---|---|---|
| promaid-mod `experimental/memory-port` | `7f891d6` | sync: 对齐 maidmods 工作树 v1.5.377(当前部署线) |
| promaid-mod `experimental/memory-port` | `9090191` | feat(memory): 移植 Sphantosis 快速记忆索引与自动记忆归档(v1.5.378) |
| changelog | `assets/promaid/guide/changelog.txt` | `promaid 1.5.378` 条目(游戏内手册可见) |
