# 实测九百四十二 手册配置面板重组（第一批：物品大类 / 归类归位 / 智能挖矿默认开）

> 跑道：开发者一次性点名一批「手册内模组详细配置的排版调整」。上一轮：`rounds/R941-mining-stall-recovery.md`。
> 本轮依据：开发者原话（见下）+ 面板 `PromaidConfigScreen` 的 Group/Section 结构。

## 排班

- 本批含多项面板结构改动，**分批交付**：本轮 = 结构 + 文案 + 默认值（可编译、可护栏、可部署）；危险方块表改勾选、压缩盒差异化、挖矿/伐木配置重写、其余开关全量归位留作后续增量。
- 步骤：改 Forge → 镜像 Neo（逐锚点断言唯一，命中数 ≠ 1 即中止）→ 两树 `javac EXITCODE=0` → 全护栏 `FAIL 0` → 打 jar → 部署 4 实例 `match=True`。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

开发者原话（节选）：

1. 大面板调整：**移动与行为**里面仅保留 飞行跟随、扫帚模式、搭路、空闲与流畅；
   **系统与杂项**去除压缩盒；其他 Promaid 添加的**物品单独加一个功能大类「物品」**，
   每个物品占一个小类，点开小类能看到该物品的图标。
2. 生存与复活里：末影珍珠逃生的配置应放**自保战术**；区块相关配置不该在这里，应放**杂项**。
3. 危险方块表的填充方式应改成**跟挖矿一样的配置方式**（勾选）。
4. **传送与逃生**里很多是很老的配置：有用的改注释、**不要出现版本号**；没用的废掉。
5. **战斗与自保 → 接敌机动** 改名为 **空中接敌机动**。
6. toml「其余开关自动」里的项目也应一一按到对应的格子里（不再单独列一堆杂项）。
7. 挖矿「隔墙透视」改为**默认开启**，改名 **智能挖矿**，描述：女仆会自己感知和尝试挖矿，不再只挖表层的。
8. 语音与 TTS：系统消息朗读能否生效？玩家能否自己导入语音包？（→ 见 `## 验证` 末尾的核查结论）

## 定位与改法（本轮落地项）

- **「物品」大类**：`Group` 新增 `ITEM("\u00a7e物品")`；把 `Section.COMPRESSION_BOX`（原 `Group.SYSTEM`）、
  `TETHER`（武装拴绳）、`RIDE`（骑乘指挥棒）、`DA_YUN`（饰品「大运」）四件道具的小类挂到 `Group.ITEM`——
  每个物品一个小类。新增 `sectionIcon(Section)`：点开物品小类时在标题右侧画该物品的 `ItemStack` 图标
  （压缩盒 / combat_leash / ride_baton / da_yun）。
- **「移动与行为」精简**：该大类下只剩 飞行跟随 / 扫帚模式 / 搭路 / 空闲与流畅（`FLIGHT_FOLLOW` / `BROOM` /
  `BRIDGE` / `IDLE`）；`TETHER`/`RIDE`/`DA_YUN` 移入「物品」；`FOLLOW`（移动与跟随）移入「系统与杂项」。
- **生存与复活拆分**：`COMBAT_PEARL_COOLDOWN/RATIO/DIST` 三行从 `escapeRows()` 移入 `selfTacticsRows()`
  （自保战术，带小节标题「末影珍珠逃生」）；`MISC_MAID_CHUNK_LOAD`（女仆区块持续保载）从 `safetyRows()`
  移入 `utilityRows()`（交互与杂项）；`Section.SAFETY` 由「女仆安全与区块」改名「女仆安全」。
- **接敌机动改名**：`MANEUVER` 小类标题 + 页内 11 处「接敌机动」全部改为「空中接敌机动」。
- **智能挖矿**：`MINE_SEEK_THROUGH_WALLS` 的 toml 默认 `false → true`，描述与面板行标签改为
  「智能挖矿（默认开）：女仆会自己感知并尝试挖矿，不再只挖表层的……」。
- **陈旧文案**：`escapeRows()` 的「传送安全判定半径」注释去掉 `v1.5.150 起` 版本号。
- **护栏同步**：默认翻转使 DIY 关闭条数 +1 → `check_preset` 基线 `172/176 → 173/177`；
  重跑 `tools/verify/gen_switch_inventory.py --write` 刷新 `ARCHITECTURE-SWITCHES.md`。

## 触及文件

- 两树 × `com/maidsmart/config/PromaidConfigScreen.java`（Group/Section、行迁移、图标、文案）
- 两树 × `com/maidsmart/config/MaidSmartConfig.java`（`seekThroughWalls` 默认与注释）
- `tools/verify/check_preset.py`（DIY 基线 173/177）
- `ARCHITECTURE-SWITCHES.md`（重生成）
- `rounds/RELEASES.tsv`（第八十一次同号重打）+ `rounds/R942-panel-restructure.md`（本文件）

## 验证

- **两树编译**：`compile_promaid.bat` / `compile_neo.bat` → `EXITCODE=0`（Forge 18 处锚点；
  Neo 侧 `drawCenteredString/renderItem` 与 Forge `m_280653_/m_280480_` 名称差异已按树分别供给，
  全 18 处命中）。
- **镜像自查**：Forge 侧 `Component.m_237113_` / `g.m_280653_` / `g.m_280480_`；Neo 侧
  `Component.literal` / `g.drawCenteredString(this.font, …)` / `g.renderItem(…)`。
- **全护栏**：11 个全 `FAIL 0`（`check_comment_style` **PASS**；`check_panel_coverage` **FAIL 0 / WARN 0**
  ——行迁移未使任何 toml 键失覆盖；`check_preset` **FAIL 0 / WARN 0**、DIY 基线 173/177；
  `check_workflow` 3 条历史 WARN；`check_tree_mirror` 只读报告）。
- **两个 jar**：forge **17588940** 字节（sha256 `1A8487B4…9B9B29`）；
  neoforge **17625258** 字节（`3C2B09F4…4E38F6A`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 行更新 sha256 并列第八十一次同号重打；
  九百四十一 的旧字节 `76532293…777EA3`（forge）/ `615ECCFC…73C503`（neo）作废。
- **TTS 核查结论（回答开发者第 8 问）**：
  - **系统消息朗读：能生效。** 链路完整——`ChatBubbleLimitMixin` 拦 `ChatBubbleManager.addTextChatBubble`
    → `SystemTTSManager.speak` → 命中内置 jar 语音包（`assets/promaid/voice/`，175 条映射）→
    `PlayJarVoicePacket` → 客户端 `PromaidVoiceSoundInstance` 播放；默认开
    （`voice.systemEnabled` / `voice.jarPackEnabled` 均 true）。磁盘语音包 / 缓存 / TLM 合成回退也都在。
  - **玩家能否自己导入语音包：能。** 面板「语音」页有「导入路径」+「导入语音包」（选 `.zip`）+
    「重新加载」，`SystemVoicePack.importPack` 把 `manifest.json`+`*.ogg` 拷进
    `config/maid_smart/system_voice/`；多人下需 OP(2)。**两个注意点已如实记录**：① 音频走的是
    原始字节加载（jar classloader / 配置目录 `Files.readAllBytes`），**不是** `ResourceLocation`
    音效，所以**传统资源包覆盖不了**，只能用这个导入路径；② 内置 jar 包优先级最高，若某句台词已被
    内置包命中，用户包要覆盖它得先关掉 `voice.jarPackEnabled`。
- **未跑实机回归**（已部署，等开发者实测）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- **判据**：
  - 首页多出「物品」大类；点进去有 压缩盒 / 武装拴绳 / 骑乘指挥棒 / 饰品「大运」四个小类，
    点开任一，标题右侧出现该物品图标。
  - 「移动与行为」里只剩 飞行跟随 / 扫帚模式 / 搭路 / 空闲与流畅。
  - 「战斗与自保」里原「接敌机动」显示为「空中接敌机动」。
  - 「自保战术」页出现「末影珍珠逃生」三行；「传送与逃生」不再有珍珠三项；「传送安全判定半径」
    注释里没有版本号；「系统与杂项」里有「女仆区块持续保载」。
  - 挖矿页第一条显示「智能挖矿」且默认为开；关掉它才回到"只挖看得见的矿"。

## 边界（没动什么）

- **没动**：所有配置项的功能语义（除 智能挖矿 的默认值按开发者要求翻转）；挖矿/伐木表页、
  危险方块表控件仍是原样（下一增量改）；压缩盒本身的行为；TTS 子系统代码（只做核查，见上）；
  `MaidSmartConfig` 里各 `push("...")` 的 toml 段名（翻译键 `config.promaid.<section>.<key>` 因此不变，
  老 toml 不受影响）。
- **口径变更（有意，非回归）**：`mine.seekThroughWalls` 默认 `false → true`（开发者点名）；
  DIY 关闭条数 172/176 → 173/177。
- **没新增配置开关**；版本号仍 **1.3.2**。
- **已知取舍（如实记录 + 待开发者裁）**：
  1. 「移动与跟随」我暂放进**「系统与杂项」**（开发者只点了"移动与行为仅保留那四项"，没说它去哪）；
     若你想放别处，一行枚举改动即可。
  2. 物品大类目前只收了 4 件**有独立配置**的道具；蓝图手册 / 排班表 / 酿造手册 / 指标石 / 巡逻图
     等没有独立配置页，仍留在各自的功能大类里（建造 / 生产与工作 ……）。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第八十一次同号重打。
- `DEBT.md`：不新增条目——本轮为面板归类/文案/默认值调整，不引入新的未验证功能面。
  （注：`DEBT.md` TD-04「压缩盒 vs 精妙背包功能重复未裁决」与本批第 1 项相关，见后续增量。）
