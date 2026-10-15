# 实测九百一十二 干活不被召回对跟随模式生效（扩圈读数罩住「她到主人 / 到圈心」）

> 跑道：**A 修 bug**（P1 功能失效，见 `DEBT.md` BD-16）——本轮的开轮理由与唯一主项。
> 本轮**不是纯功能轮**（`WORKFLOW.md` §8「P0/P1 未清期间不得开纯功能轮」照旧成立——BD-04 仍挂着未清），
> 且本轮已清掉一条 P1（BD-16），配额口径两侧都满足。

## 排班
- 开发者指令：「伐木可以正常运行了，但是有些地方还是不好。首先是那个原版的女仆自己走回来的机制没有遏制好。
  女仆还是会在稍微走了几步之后又走回来，然后在目标跟主人之间反复走动。更糟糕的是。传送机制落实到位了，
  导致女仆在工作期间，如果主人离得太远，不会进行传送，反而是在原地走一步停一步。」
- 定位顺序：先照 R911 那条扩圈的**唯一读数**（`EntityMaid.getRestrictRadius`）反查"谁还在读它、读出来的值够不够"，
  再用 `javap`（`libs_forge/original_tlm.jar`）确认跟随行为在"传送被拦下"之后**还做了什么**。

## 定位（BD-16）
- R911 的扩圈带两道自限：`if (!maid.m_21536_()) return normal;`（非 home 模式直接不扩）与「只放到检索半径 + 8」
  （默认 24 + 8 = 32）。**两道自限叠上默认配置后，扩圈其实两种模式都没生效**：
  - 跟随模式（默认）：她这份半径读的不是 home 的 `MaidWorkRange`（默认 **64**），而是 `MaidNonHomeRange`
    （默认 **8**，`MaidConfig` 实证）；`isWithinRestriction` 对非 home 恒 true，"太远"判据量的是**她到主人的距离**
    ⇒ R911 那道 `!home → 原样` 直接把她排除在外。
  - home / 排班模式：读数取 `max(常态, 32)`，而常态 = `MaidWorkRange` 默认 64 ⇒ **32 被 64 吃掉**，同样空转。
- 于是跟随行为（`MaidFollowOwnerTask.start`，**每 tick 都跑一次**：它没覆写 `canStillUse`，父类 `Behavior.m_6737_`
  默认 false → RUNNING 只维持一拍就 doStop 回 STILL）照旧读那 8 格半径，跟随判据只有 6 / 10 两档：
  ① 距离落进 `(半径 − 2, 半径 + 2]` = 6~10 格 → 走"把主人写回她的 `WALK_TARGET`"那一支，`setWalkAndLookTargetMemories`
  与干活的目标每拍互相覆盖 ⇒ **在目标与主人之间反复走动**（开发者说的"稍微走了几步之后又走回来"）；
  ② 超过 10 格 → 走传送支，`teleportToOwner` 被 `misc.workTeleportHold` 拦成 false，**但返回值被 `pop` 丢弃**，
  紧接着照样 `resetNavigation()` = `setNavigation(basicNavigation)` + `stop()` + `mode → GROUND` ⇒ 每 tick 清掉
  她刚起步的寻路 ⇒ **原地走一步停一步**（开发者说的第二种现象）。
- 一句话：R911 只拦了"传送这个动作"，没拦"跟随行为本身"；而跟随行为的判据读数（默认 8 格）根本不在扩圈范围内。

## 修法 / 落刀清单
1. **扩圈读数扩到位（BD-16，唯一落刀点）**：`com.maidsmart.task.MaidWorkRangeExpand` 的 `radius()` 改为
   `max(常态, max(检索半径, 她到主人的距离, 她到圈心的距离) + 8)`，并**删掉 `!isHomeModeEnable() → 原样` 那道自限**。
   - 为什么这样够：跟随行为的两个分支都以"距离 ≤ 半径 ± 2"为**前置判据**——读数顶过"她到主人"这段距离，
     两个分支连进都进不去，于是既不传送、也不走回来、**也不清寻路**（一处读数把三件事一起关掉，与 R911 同口径）。
   - home/排班那条"出圈（半径 + 4）就传送回工位"同理被顶过去；新增的"到圈心"一项就是为它。
   - 无主 / 主人跨维度 / 圈心未配置（`BlockPos.ZERO`）时对应那一项不计（那几种情况下跟随与排班本来就不动她）；
     跨维度一项照 `MaidFollowOwnerTask.ownerStateConditions` 的口径判，避免把"不同维度的主人"算成拉回源。
   - 客户端仍恒返回常态值（"在不在干活"客户端拿不到，硬猜会画出与服务端不一致的圈）；开关仍是既有
     `misc.workTeleportHold`，不新增配置项（`R902` 刚做过配置瘦身）。
2. **不动跟随行为本身**：不新增/不改 `MaidFollowOwnerTask` 的 mixin。理由：该行为在官方 jar 与"改版 jar"
   （整合包那份把距离逻辑提取成 `tryFollowOwner` 并加 tick 覆写）里方法名不同，硬挂钩子会挑 jar；
   而半径读数是两边共用的**唯一读数**（`CombatWorkRange` 早已用它实现战斗扩圈，实机在用）。
3. **注释/文案同步**：`MISC_WORK_TELEPORT_HOLD` 的配置注释改成新口径（罩住她到主人/圈心/检索范围；
   并点明跟随那两个分支还会每 tick 清寻路）；zh/en 文案不动（"传送 + 工作圈临时扩圈" 仍准确）。
4. **测绘/门禁同步**：`tools/arch/arch_modules.py` 里 `MaidWorkRangeExpand.java` 行数 68 → 94、模块描述补一句
   （中英各一）；`ARCHITECTURE-SWITCHES.md` 按生成器重刷（配置注释变了）；`ARCHITECTURE.md` 修订哈希复核。

## 验证
- 两树编译：`compile_promaid.bat` **EXITCODE=0（465 源）**、`compile_neo.bat` **EXITCODE=0（468 源）**。
- 全套护栏：`check_comment_style` **PASS**；`check_arch` / `check_catch_swallow` / `check_comment_dup` /
  `check_dev_voice` / `check_i18n_keys` / `check_panel_coverage` / `check_preset` / `check_reflection` 全部
  **FAIL 0 / WARN 0**；`check_tree_mirror` 报告完成（只读）；`check_workflow` **FAIL 0 / WARN 3**
  （既有发布登记项，与本轮无关）。
- 结构数据：`python tools/arch/build_arch.py` 重生成（144 产物 + `normify.html`）；`check_arch` 逐字节比对
  **差异 0**、neo 未覆盖 **181 = 基线**、`ARCHITECTURE.md` 修订 `f94c12de`（= 最近一次改动源码的提交）一致。
- 镜像保真：两树 `MaidWorkRangeExpand.java`（各 94 行）`fc` 比对，差异仅映射名
  （`m_9236_()`↔`level()`、`m_5776_()`↔`isClientSide()`、`m_269323_()`↔`getOwner()`、`m_6084_()`↔`isAlive()`、
  `m_20280_()`↔`distanceToSqr()`、`m_21536_()`↔`hasRestriction()`、`m_21534_()`↔`getRestrictCenter()`、
  `m_123331_()`↔`distSqr()`、`m_20183_()`↔`blockPosition()`、`BlockPos.f_121853_`↔`BlockPos.ZERO`）。
- 未跑实机回归（`WORKFLOW.md` §4 第 8 步后一律先停）。打 jar 与部署见下「部署」小节。

### 部署（2026-10-12，开发者指令：「以后做完功能都是先部署，但后续的工程不要做，等我指挥」）
- **打 jar**：`tools/build/build_promaid.py` → `patched/promaid-1.3.2-forge-1.20.1.jar`（**17584584** 字节，
  sha256 `08c8d209…7b2f77`）；`build_promaid_neo.py` → `patched/promaid-1.3.2-neoforge-1.21.1.jar`
  （**17622658** 字节，sha256 `ed86c3a8…ff54de`）。两树 `verify_jar_classes` **OK**（mixin 包登记 Forge **97** /
  Neo **96** 个类全部登记、无未登记「鸭子接口」；**mixin 注入签名无歧义/错名**；out 与 jar 的 class 集合双向一致），
  lang json **OK**、`MISSING: none`；构建末尾全套护栏 **FAIL 0**（`_mixchk.py` 不在库内、构建按约定 WARN 跳过，非本轮引入）。
- **装机**：`tools/deploy/deploy_all.py` → 4 个本机实例全部 **`match=True`**（与 `patched/` 逐字节一致）：
  1.21.1-NeoForge_21.1.250（17622658）/ 1.20.1-Forge_47.4.10（17584584）/ 龙之冒险：新征程v2.4a / mc_server_test pack1201。
  旧包已备份到 `patched/backup_old/`（第五十次的 17622212 / 17584138）。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行的 sha256 列更新为新字节，并追加「第五十一次同号重打」记录，
  旧字节 `083a2802…120a61` / `1a1dc898…0cd1dd` 作废（第五十次 R910 + R911）。
- **到此为止**：开发者已把「做完功能先部署」定为常态，但明确「后续的工程不要做」——**未跑回归、未验收、未 commit、未 push**。

## 验收
- 状态：`未验收`（已部署@2026-10-12：本机 4 实例与 `patched/` 逐字节一致；未 commit、未 push）。
  - 复现步骤 1（跟随模式 = 默认，**主项**）：女仆 **不点 home / 不排班**，给伐木（或挖矿）任务，主人站着不动 →
    她走向目标后**不应**再"走几步又掉头回主人身边"（原版 6 格就开始往回走）；把主人往远处挪
    （> 10 格，原版此时直接传送）→ 她**不应**被传送回来、也不应"原地走一步停一步"（应继续把手上这棵树/这块矿干完）。
  - 复现步骤 2（home / 排班模式，回归确认）：工作区设小（如 12 格），home 下挖矿/伐木 → 出去找目标期间
    **不应**被"出圈传送"拽回工位；目标干完后把她丢在远处 → 过一会儿应看到原版的召回生效（读数回落常态）。
  - 复现步骤 3（开关）：关掉 `misc.workTeleportHold` → 上述两条都回到 TLM 原版（照常被传送/拽回）。
  - 判定：步骤 1 不再来回走、不再走一步停一步 = 合格；异常请把 `logs/promaid.log` 相关几行贴回。

## 边界（没动什么）
- 未改跟随行为本身（无新 mixin）；未改战斗扩圈的任何数值/判据；未新增配置项（复用 `misc.workTeleportHold`）；
  未改版本号（仍 1.3.2）；已打 jar + 部署本机 4 实例（见「部署」小节），**未跑回归、未验收、未 commit、未 push**。
- **流程口径（开发者 2026-10-12 指令）**：以后**做完功能先部署**，部署完就停——回归 / 发布 / commit 等后续工程
  一律等开发者指挥。已按此改 `WORKFLOW.md` §4 第 8 步的前提说明（旧口径「没说部署就只到第 7 步」作废）。
- 已知口径（有意保留）：扩圈的判据是"该工作行为正在跑"（`isMining()` / `isWooding()`，与"拾取让位"同一口径），
  不是"这一刻手里正锁着一块目标"——因此任务挂着但附近没活时，她也不会被立刻召回，而由行为自己的
  换框/迁移逻辑（跟随模式会朝主人方向挪搜索框）把她带回来。若要收紧到"锁定目标期间"，需要给两个行为
  加一枚 per-女仆 的"当前有目标"标记（落刀点多、本轮不做），留作专项。
- 临时脚本/日志（`_c1.log`/`_c2.log`/`_g_*.log`/`_ba.log`/`_si.log`/`_jav_*.txt`/`_js.txt`/`_jm.txt`/`_jcfg.txt`/`_cw.txt`）用后即删。

## 台账
- `DEBT.md`：新增 **BD-16**（P1，已清@实测九百一十二，含 `javap` 证据链）+ §0 索引行更新。
- 待办（下一轮或开发者指令）：实机验收；验收/部署后按 `WORKFLOW.md` §4 第 10 步开闸再跑回归；
  `git commit` 后重跑一次 `python tools/arch/build_arch.py`（提交后修订哈希会前移，`check_arch` 会以 WARN 提示，属预期）。
