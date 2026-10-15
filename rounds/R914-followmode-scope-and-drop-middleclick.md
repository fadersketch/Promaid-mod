# 实测九百一十四 走位独占与干活扩圈收敛到跟随模式 + 删除中键工位标记

> 跑道：**A 修 bug**（P1：R911~R913 的"干活扩圈 / 走路逻辑独占"越界到 home 模式，见 `DEBT.md` **BD-18**）
> ＋一项**功能下线**（开发者裁定中键工位标记被巡逻航图取代，见 `TECH_DEBT.md` **TD-04** 部分清偿）。
> 两者都是本轮的开轮理由与主项。本轮**不是纯功能轮**（`WORKFLOW.md` §8「P0/P1 未清期间不得开纯功能轮」照旧
> 成立——BD-04 仍挂着未清），且本轮已清掉一条 P1（BD-18），配额口径两侧都满足。

## 排班

- 开发者指令（一次给全）：「我上述提到的功能只在跟随模式下生效，如果是 home 模式，那么它划定的工作范围就是
  它的最大活动范围，它不会超出这个范围。（反正超过了也会自己传送回来）。现版本的中键锁定工作区域已经失去了
  意义，因为已经有了巡逻航图。把这个功能删掉。」
- 拆成两件事：
  - **A**：上一轮（R913）的"干活/接战期间原版走路逻辑整体让位"与更早（R911/R912）的"干活时临时扩圈"，
    都**只该在跟随模式下生效**；home（不跟随 / 排班）模式下那道工作圈**就是她的最大活动范围**，她不该出圈，
    出了圈被 TLM 传送回工位是**预期行为**，不需要被顶开。
  - **B**：把"中键锁定工作区域"（潜行 + 中键标记工位）整条功能**删除**——玩家现在用**巡逻航图**表达
    "她在哪一块活动"，中键这套既重复又更弱。

## 定位（BD-18）

- **A 的越界在哪**：R911 给"干活时不被召回"加了 `MaidWorkRangeExpand`（挂在 TLM 的 `getRestrictRadius`
  这一个读数上）+ `WorkRangeExpandMixin`；R912 为了修**跟随模式**（跟随读的是 `MaidNonHomeRange` 默认 **8**，
  不是 home 的 `MaidWorkRange` 64）把当时那道 `!isHomeModeEnable() → 原样` 自限**一并撤掉** → 扩圈在
  **两种模式都生效**。R913 的 `MaidWorkTags.isChainDrive`（走位独占标记）同理**没有 home 判据**。于是 home
  模式下：她照样能顶着那道圈往外走，观感就是"该回来不回来"。开发者要的是**把这两条收回到跟随模式**。
- **判据从哪来**：home 模式 = 有 `SchedulePos` 限制（TLM 的 `EntityMaid.m_21536_` / `hasRestriction()`，
  等价于 `isHomeModeEnable()`）。首判为真 → 扩圈原样返回、独占恒 false，圈内走位**照归 TLM**。
- **B 的足迹在哪**：中键功能 = 客户端手势类 `marker/WorkPosMarkerClient`（潜行 + 中键，`MARK_REACH = 8`，
  会礼让河童罗盘 / 巡逻航图）+ 网络包 `MarkWorkPosPacket`（写 `SchedulePos.setWorkPos/setIdlePos` +
  `restrictTo` + `PlayerCircle.markPlayerCircle`）+ 开关 `misc.workPosMarker`。其中 `PlayerCircle` 的
  「玩家圈」记录与 `smart_work_area` **工具**是**另一回事**（罗盘那条链 + LLM 指挥工具），必须保留。

## 修法 / 落刀清单

1. **扩圈收敛到跟随模式**（`task/MaidWorkRangeExpand` 两树）：`radius()` 在客户端/异常检查之后新增一道
   `if (maid.m_21536_()) return normal;`（neo：`maid.hasRestriction()`）——home 那道圈就是上限，出圈被传送
   回工位是预期。`holdRange` 简化成只有"到主人的距离"一项（删掉已成死代码的"到圈心"分支，`BlockPos` import
   一并去掉）；类文档改写成「**跟随模式**下…」并加一段 `【home 模式不扩（实测九百一十四）】`。
2. **走位独占收敛到跟随模式**（`task/MaidWorkTags` 两树）：`isChainDrive()` 在坐着/被骑/脑冻结检查之后新增
   `if (maid.isHomeModeEnable()) return false;`——圈的走位照归 TLM，独占只在跟随模式生效；类文档同步。
   **接战翻倍同理**（`combat/CombatWorkRange` 两树，开发者追加指令「不翻倍，home 范围是死规矩」）：
   `radius()` 在客户端检查之后新增 `if (maid.m_21536_()) return normal;`（neo 侧 `maid.hasRestriction()`）
   ——home 模式下接战也不扩圈，那道圈是死规矩；类文档与 `radius()` 文档同步。
3. **删除中键工位标记（B）**：
   - 删文件：两树 `com/maidsmart/marker/WorkPosMarkerClient.java`（连空的 `marker` 包目录）。
   - 删网络包：`schedule/SchedulePacketsMaid` 里整个 `MarkWorkPosPacket` 类（forge 的 `public final int x,y,z`
     + encode/decode/handle；neo 的 `CustomPacketPayload` + `TYPE`）；`schedule/ScheduleNetworking` 去掉注册
     （forge 频道索引 **18** 留空并注释「已删除——功能下线，索引不补位」；neo 去掉 `playToServer`）。
   - 删挂载：`ProMaidMod` 的 `PromaidClientSetup.registerWorkPosMarker()` 调用、`client/PromaidClientSetup`
     的同名方法（两树）。
   - 删开关：`config/MaidSmartConfig` 的 `MISC_WORK_POS_MARKER`（声明 + define）、`config/PromaidConfigScreen`
     的「中键工位标记」行、`config/MaidConfigPreset` KEEP 里的 `"misc.workPosMarker"`、两树
     `assets/maid_smart/lang/{zh_cn,en_us}.json` 的 `config.promaid.misc.workPosMarker`。
   - 改写残留引用：`PlayerCircle`（类文档 / `PLAYER_TAG` 注释 / `markPlayerCircle` 注释 / 「不干预」段）、
     `ScheduleManager` 诊断串、`follow/WorkAreaClamp`、`fishing/FishingChairService`（注释 + 气泡文案）、
     `tool/WorkAreaTool`、`ProMaidExtension`、`guide/GuideChaptersSystem`、`MaidSmartConfig` 的
     `TOOL_WORK_AREA` / `MISC_COMPASS_PRIORITY` 注释、`PromaidConfigScreen` 的 `TOOL_WORK_AREA` /
     `MISC_COMPASS_PRIORITY` / `SCHEDULE_ACTIVITY_RANGE` 行文案——凡提"中键"的一律删去，只留「河童罗盘 /
     巡逻航图」口径。
4. **中键残留的文案/译文/语音清理**：把 `PromaidI18n` 那张整串英译表里所有指向已删功能的条目处理干净——
   删掉功能独占的 5 条（配置注释 / 面板名 / 面板描述 / 罗盘让位提示 / `工位标记已发送（`）+ 3 条已失效的
   手册长句（"工作区域与工位标记""和河童的罗盘是互补关系""罗盘圈优先"）；把 8 条仍在使用、但正文提到中键的
   条目按当前源码重写 key 与英文 value（`smart_work_area` 两处、罗盘圈优先两处、排班活动半径、手册
   `smart_work_area` 那一行、女仆气泡、DIY 保留清单——后者顺带把"工作位置标记"从"保留"名单里去掉）。
   同步清理：`RideBindManager` 的"口径与…同款"引用、`PatrolMarkerClient` 里对已删 `WorkPosMarkerClient`
   的整段关系说明、`SchedulePacketsMaid` 类注释里的包种列举、`WorkAreaTool` 的英文串、女仆语音的
   `manifest.json` 台词与 `tools/voice/gen_voice_pack.py` 的日文台词。
5. **测绘/门禁同步**：`tools/arch/arch_modules.py` 删 `promaid.core.marker` 记录、`promaid.core` 模块描述里
   去掉"工位标记 / work-position marker"；`ARCHITECTURE.md` 规模行 121 → **120** 模块；重跑 `build_arch.py`
   （120 模块 / 61 API / 73 箭头 / 深度 3）、`gen_panel_coverage.py`、`gen_switch_inventory.py --write`。

## 验证

- 两树编译：`javac @compile_promaid.txt` **EXITCODE=0（464 源）**、`javac @compile_neo.txt` **EXITCODE=0
  （467 源）**（各比上轮少 1 个源 = 删掉的 `WorkPosMarkerClient`）。
- 全套护栏：`check_comment_style` **PASS**（首轮曾因 `MaidWorkRangeExpand` 类头 16 行 > 15 报 LONG-BLOCK ×2，
  压缩 home / 开关两段后 PASS）；`check_arch` / `check_panel_coverage` / `check_catch_swallow` /
  `check_comment_dup` / `check_dev_voice` / `check_i18n_keys` / `check_preset` / `check_reflection`
  全部 **FAIL 0 / WARN 0**；`check_preset` 基线 **175 / 179** 不变（删的是开关 + 数值项，不含 DIY 计入项）。
  `check_workflow` **FAIL 0 / WARN 3**（3 条均为历史遗留：PUBLISH_GUIDE 版本 1.3.1 ≠ mods.toml 的 1.3.2、
  1.2.4 归档哈希不可核、13 个更早 jar 未登记）。
- 打 jar：两树 `verify_jar_classes` **OK**（mixin 包登记 Forge **97** / Neo **96** 个类全部登记、无未登记
  「鸭子接口」；**mixin 注入签名无歧义/错名**；out 与 jar 的 class 集合双向一致，构建时清掉了残留的
  `com/maidsmart/marker/WorkPosMarkerClient.class`）；lang json **OK**；构建末尾全套护栏 **FAIL 0**。
- 未跑实机回归（`WORKFLOW.md` §4 第 8 步后一律先停）。打 jar 与部署见下「部署」小节。

### 部署（2026-10-12，开发者常态指令：「以后做完功能都是先部署，但后续的工程不要做，等我指挥」）

- **打 jar**：`tools/build/build_promaid.py` → `patched/promaid-1.3.2-forge-1.20.1.jar`（**17578945** 字节，
  sha256 `e899f791…3a22a4`）；`build_promaid_neo.py` → `patched/promaid-1.3.2-neoforge-1.21.1.jar`
  （**17616951** 字节，sha256 `9e09a027…7f6013`）。（本轮打包三次：先出一版 17579715 / 17617673，再把中键
  残留的中英译文与 `WorkAreaTool` 英文串清干净重打为 17576116 / 17614117，最后按开发者追加指令给接战翻倍
  补 home 闸、并重合成语音台词，重打为上面这版最终字节。）
- **装机**：`tools/deploy/deploy_all.py` → 4 个本机实例全部 **`match=True`**（与 `patched/` 逐字节一致）：
  1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201
  （后两个实例需提权复制，脚本走 UAC 后 rc=0）。旧包已备份到 `patched/backup_old/`
  （17621856 / 17583783，即 R913 第五十二次字节）。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行的 sha256 列更新为新字节，并追加
  「第五十三次同号重打」记录，旧字节 `d15364ac…e7920a`（forge）/ `cbd690d8…9a8ffa`（neo）作废。
- **到此为止**：**未跑回归、未验收、未 commit、未 push**（开发者明确"后续的工程不要做"）。

## 验收

- 状态：`未验收`（已部署@2026-10-12：本机 4 实例与 `patched/` 逐字节一致；未 commit、未 push）。
  - 复现步骤 1（**home 模式不扩圈**）：给女仆排班 / home 模式并圈定工作区 → 让她在圈内干活（挖矿/伐木），
    圈外的矿/树**不该**把她引出去；真走远了被传送回工位是**预期**，不再被顶开。
  - 复现步骤 2（**跟随模式照旧扩圈**）：改成跟随模式，主人站远、她走远找矿/伐木 → 圈照旧被顶开，
    不被"太远就回去"拽回、不原地走一步停一步（这是 R913 已修的，本轮确认**没被 home 闸误伤**）。
  - 复现步骤 3（**中键功能已消失**）：潜行 + 中键点女仆 → **不该**再标记她的工位 / 移动她的活动圈；
    河童罗盘写圈与命令行 `smart_work_area` 工具**仍在**（回归确认没删过头）。
  - 判定：步骤 1 不出圈、步骤 2 仍能追出去、步骤 3 中键无反应而罗盘/工具可用 = 合格；异常请把
    `logs/promaid.log` 相关几行贴回。
- 面板/文案验收：配置面板里**不应**再出现「中键工位标记」开关；`TOOL_WORK_AREA` / 罗盘优先级 /
  排班活动范围三行文案**不应**再提"中键"。

## 边界（没动什么）

- **接战翻倍已加 home 闸**（开发者追加指令「不翻倍，home 范围是死规矩」）：`combat/CombatWorkRange.radius()`
  在客户端检查之后补 `if (maid.m_21536_()) return normal;`（neo：`hasRestriction()`）——home 模式下接战也不扩圈，
  那道圈是死规矩；只有跟随模式仍按 `max(常态 × 2, combatWorkRange)` 扩（BD-16 最初那条 home 自限的口径至此反转）。
- **语音已重合成**（开发者追加指令「重合成语录」）：女仆那句"没有能坐下的水边"的日文台词改写成
  "用河童的罗盘指定水边方块"后，用本机 GPT-SoVITS（`tools/voice/gen_voice_pack.py run fish_no_water_area.ogg`
  → `norm_voice_pack.py` 归一化编码 → `install_voice_pack.py`）重新合成并装回两树 `assets/promaid/voice/`；
  中文台词（manifest key）、日文台词表、ogg 三者现已一致。
- 未动 `PlayerCircle` 的玩家圈记录与 `smart_work_area` 工具（那是河童罗盘 / LLM 工具那条链，与中键无关）。
- 未重写挖矿/伐木链路、未改工具/连锁/搭方块/换镐等既有机制；未改版本号（仍 **1.3.2**）。
- **`ARCHITECTURE-SWITCHES.md` / `panel_coverage.txt` / `normify-promaid/` 已按真源重生成**；
  `tools/arch/arch_modules.py` 里各代表文件的 `end_line` 数字**未动**——实测该表两百余条里已有大量历史漂移
  （`check_arch` 只校验产物与真源逐字节一致、不校验行号），留作测绘侧专项。
- 临时脚本（`_g.py`）用后即删。

## 台账

- `DEBT.md`：新增 **BD-18**（P1，已清@实测九百一十四：R911~R913 的介入越界到 home 模式）+ §0 索引行更新
  （BD-17 · BD-18 并列，措辞改「跟随模式下…home 圈是最大活动范围」）；`TECH_DEBT.md` **TD-04** 由「未清」
  改为「**部分清偿**」（中键设工作位置 = 删除@实测九百一十四，含整套删除清单；剩余两项"落地水 / 压缩盒"仍未裁决）。
- 待办（下一轮或开发者指令）：实机验收；验收/部署后按 `WORKFLOW.md` §4 第 10 步开闸再跑回归；
  `git commit` 后重跑一次 `python tools/arch/build_arch.py`（提交后修订哈希会前移，`check_arch` 会以 WARN 提示，属预期）。
