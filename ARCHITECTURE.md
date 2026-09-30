# Promaid 架构说明 / Architecture

> 本文件是**架构总入口**。真正可下钻、可校验的结构数据在 `normify-promaid/`；
> 用浏览器打开 `normify-promaid/normify.html` 即可逐层点开。

- **修订**：`05103ae`（生成时 HEAD）
- **规模**：112 模块 / 41 API / 61 依赖箭头 / 最大深度 3
- **校验**：L1（写时）+ L2（全项目）+ L3（构建冻结）全部 **0 error**
- **一键重建**：`python tools/arch/build_arch.py`（等价于下面三条依次跑）
- **校验入口**：`python tools/arch/gen_arch.py` → `python tools/arch/render_arch.py` → `python tools/arch/finalize_arch.py`

---

## 1. 三十秒看懂这个项目

Promaid 是 **车万女仆（Touhou Little Maid, TLM）的增强附属模组**。它给女仆加了：
驾驶（载具/坐骑/扫帚/鞘翅/自由飞行）、战斗（空袭/轰炸/自保/武装拴绳）、生产（挖矿/伐木/建造/酿造）、
以及一层「心智」（长期记忆 / 人格 / 情绪 / 对话 / 语音）。

TLM 本身**不能被 fork**，所以本模组的一切改动都靠两类扩展点接入：

| 通道 | 手段 | 规模 |
|---|---|---|
| **Mixin 注入** | 直接改写 TLM / 原版类的方法与字段 | 85 个（68 common + 17 client） |
| **扩展注册** | TLM 的 `@LittleMaidExtension` / `IMaidTask` / `ILittleMaid` 回调 | 见 `promaid.entry` |

**代码是手工镜像的两份**：`promaid_src`（Forge 1.20.1，SRG 名如 `m_20148_`）与
`promaid_src_neo`（NeoForge 1.21.1，Mojang 名）。两树不共享源码、不共享构建产物 —— 每个功能都要写两遍。
**这是本仓库最大的结构性技术债**，见第 4 节。

---

## 2. 顶层脉络（8 个域）

```
promaid
├── entry      入口与装配      模组启动、注册枢纽、礼物、创造栏
├── mixin      注入层          85 个 mixin，按"补丁谁"分成 10 组
├── combat     战斗与载具      76 文件 / 2.77 MB —— 体量最大、技术债最集中
├── work       生产类任务      挖矿 伐木 烧制 酿造 宰杀 搭路 + 整套蓝图建造
├── flight     飞行            仿创造模式自由飞行（空袭的战斗飞行在 combat）
├── social     社交与心智      记忆 人格 情绪 对话 语音（5 层）
├── system     系统服务        区块加载 跨维跟随 排班 保护 压缩盒 酿造 Goety
└── core       核心与配置      配置定义与面板 指令 LLM 工具 建构安全过滤器
```

| 域 | 模块数 | 一句话 |
|---|---|---|
| `entry` | 6 | 一切从这里被接上 TLM |
| `mixin` | 10 | 唯一能深耦合的合法通道 |
| `combat` | 17 | 从主动参战到骑乘指挥棒 |
| `work` | 21 | 女仆的职业 + 蓝图建造系统 |
| `flight` | 3 | 自由飞行能力层 |
| `social` | 12 | 记忆/人格/情绪/对话/语音 |
| `system` | 13 | 「别丢、别卡、别漏」的底层服务 |
| `core` | 12 | 配置、指令、工具、过滤器 |

---

## 3. 四条主链路（读代码的顺序）

### 3.1 启动装配链

```
@Mod ProMaidMod            → 注册物品/网络/配置，跑历史默认值迁移
  └─ @LittleMaidExtension ProMaidExtension
       ├─ addMaidTask()          登记全部任务（挖矿/伐木/建造/空袭/扫帚/酿造…）
       ├─ addExtraMaidBrain()    登记 core/rest 行为与优先级
       ├─ registerAITool()       登记给 LLM 用的工具
       └─ onServerTick()         驱动所有服务端每 tick 模块
```

> ⚠️ `ProMaidExtension` 是**全模组唯一装配中枢**，也是最大耦合点（779 行、几十条注册）。
> 任何新功能都必须在这里接线，所以它天然是改动冲突高发区。

### 3.2 一条战斗链路（以空袭为例）

```
AutoCombatSwitch   主人被攻击 → 威胁评分 → 从任务池抽一个攻击任务
  └─ MaidFlightCombatBehavior   鞘翅起跳 → 烟花推进 → 爬升/环绕
       ├─ FlightTargeting      以自身为圆心 50 格强制索敌
       ├─ （收翅俯冲）          近战一记，重锤按玩家同款下落加成
       ├─ MaidBombing          顺带放炸弹（末地水晶/重生锚/床/TNT）
       └─ MaidFlightRecall     100 格内找不到主人 → 立刻传送回来
```

### 3.3 一条生产链路（以建造为例）

```
BlueprintBookItem 右键 → BlueprintBookScreen（6 个视图）
  └─ BlueprintBuildExecutor.execute()   解析 → 重叠拒绝 → 障碍告警 → 已建扫描 → 材料预检
       ├─ BuildPlan                     计划数据（原点/维度/朝向/光标/已放置集）
       ├─ BlueprintFileIo / Codec       litematic / schem / schematic → 归一化步骤表
       └─ MaidBuildBehavior             按光标每 N tick 放一块（四档速度）
            ├─ BuildContainerFetchBehavior  缺料时去箱子拿
            └─ PlacedBlockTracker           垫脚方块事后回收
```

### 3.4 一条社交链路

```
AiMemoryManager   事件源注入
  └─ AiMemoryExtractor   后台 LLM 提取（独立 HttpClient）
       └─ AiMemoryStore  五表 jsonl 落盘 + AiMemorySearch（混合召回 + RRF）
ProactiveDialogueManager  7 阶段状态机 ──┐
AffectManager             PAD 情绪层    ─┼→ 注入 prompt → LLM 回复
PersonaPackage            人格种子（只读）┘
  └─ SystemTTSManager   四级语音来源朗读
```

---

## 4. 结构数据怎么用

`normify-promaid/` 是一份**可校验、可冻结**的结构数据，不是一次性生成的图：

| 产物 | 内容 |
|---|---|
| `modules/**/*.md` | 结构数据本体：frontmatter（id/parent/双语文案/source 证据/指纹/tags/apis/deps）+ 正文 |
| `renders/**/*.json` | 每个容器一层「这一层怎么画」：顺序 / 分组 / 导语 |
| `sidecar.json` | id → 文案 / source / API 的平表（渲染器读它，不解析 YAML） |
| `tree.json` | 编译产物：模块字典 + API 索引 + 边 + 统计 |
| `outline.md` | 人类可读大纲 |
| `api-index.json` | `protocol:path` → 模块 |
| `policy.yml` | 架构规则（无环 / 层向 / 禁依赖 / 命名） |
| `receipt.json` | 134 个产物的 SHA-256 冻结回执 |
| `normify.html` | 单文件交互式架构图（零依赖，双击即开） |

### 契约

- **只存 `parent`**：`children` 由索引导出，不会"父子各说各话"。
- **API 只在叶子**：容器上声明 API 会被 L2 判 `api/non-leaf` 直接拒绝。
- **箭头只从 mixin 指向 feature**：mixin 打补丁在 feature 上，方向定死后依赖图无环（L2 强制 `acyclic`）。
- **`source` 是证据**：每条都带仓库相对路径与行号，L2 校验路径是否存在。
- **指纹**：`fingerprint` 由 source 路径集合确定性地算出，代码移动即变。

### 重新生成

```
python tools/arch/build_arch.py        # 一键三步（推荐）

# 或分步（便于单独排错）：
python tools/arch/gen_arch.py        # 写结构数据（L1+L2 门禁，0 error 才落盘）
python tools/arch/render_arch.py     # 渲染单文件 HTML
python tools/arch/finalize_arch.py   # 覆盖 receipt.json（HTML 之后重算，避免哈希过期）
```

> `revision` 取的是**最后一次改动 `promaid_src*` 的提交**，不是 HEAD ——
> 只改文档/架构数据时重跑，产物一个字节都不会变（可重复、可校验）。


改代码后同步结构数据：改 `tools/arch/arch_modules.py` 里对应模块的 `source` / `description`，
重跑三步即可；`receipt.json` 会告诉你哪些文件变了。

---

## 5. 想改某处，该看哪些文件

| 要做的事 | 入口文件 |
|---|---|
| 加一个新任务 | `promaid_src_neo/com/maidsmart/ProMaidExtension.java`（`addMaidTask`） |
| 加一个新 AI 工具 | 同上（`registerAITool`）+ `tool/` |
| 改女仆某个原生行为 | `mixin/` 里找对应目标类的 mixin；站桩类交互看 `task/MaidWorkTags.java` |
| 改配置项 | `config/MaidSmartConfig.java`（定义）+ `config/PromaidConfigScreen.java`（界面） |
| 加一个 UI | 参照 `box/`（物品+界面+网络包三件套） |
| 改空袭/扫帚 | `combat/MaidFlightCombatBehavior.java` / `combat/MaidBroomDrive.java` |
| 改载具/骑乘 | `combat/RideBindManager.java`（绑定语义）+ `combat/MaidMountCompat.java`（各载具适配） |
| 改建造 | `build/BlueprintBuildExecutor.java` → `build/MaidBuildBehavior.java` → `build/BlueprintLib.java` |
| 改记忆/对话 | `memory/AiMemoryStore.java` / `dialogue/ProactiveDialogueManager.java` |
| 接手一个"怪 bug" | `tools/test/` 里多半已有一条编号 harness 在盯它 |

---

## 6. 技术债与屎山清单

单独成文：[`ARCHITECTURE-DEBT.md`](ARCHITECTURE-DEBT.md)（含逐文件证据、超大文件排行、死代码候选、重复逻辑）。

三条最要紧的：

1. **两棵手工镜像树**（`promaid_src` 392 文件 / `promaid_src_neo` 394 文件）—— 每个功能写两遍。
2. **`config/PromaidConfigScreen.java` 6424 行**单个立即模式界面类，含 543 行 `render()`。
3. **`combat/SelfPreservationBehavior.java` 3789 行** 与 **`combat/MaidMountCompat.java` 3657 行** ——
   单文件里堆积了上百处历史版本标记（`实测NNN`），是典型的"补丁坟场"。

---

## 7. 仓库卫生

| 项 | 说明 |
|---|---|
| `tools/arch/` | 本架构数据的生成器（可重复运行） |
| `tools/build/` `tools/deploy/` `tools/test/` `tools/voice/` | 原先散在仓库根的一堆 `*.py`，按用途归位 |
| `.gitignore` | 构建产物、本地 jar、诊断垃圾、`_*.py` 一次性脚本全部排除 |
| 历史 | 已用 `git-filter-repo` 清掉早期误提交的诊断目录与一次性修复脚本 |

> 诊断脚本一律用 `_` 前缀命名（已被 `.gitignore` 排除）。要留档的测试用例放
> `tools/test/`，并带编号与用法说明 —— 这一批编号 harness 是本项目最有价值的资产之一。
