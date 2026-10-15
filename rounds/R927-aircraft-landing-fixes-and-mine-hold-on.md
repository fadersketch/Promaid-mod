# 实测九百二十七 固定翼"还是不能降落"与挖矿"发呆几秒、我走神了"：瞄准点固定 + 顺机头选跑道 + 挖矿/伐木"咬住不放"（并删掉发呆看门狗与相关配置项）

> 跑道：**B 功能抢修**——开发者实机跑完一轮报两件。
> 开发者指令（原话）：「还是不行，飞机仍然不能具体降落。挖矿方面现在勉强可以正常运行，但是有一些方面不太好。现在基本上都是挖了一段时间之后，女仆就先发呆个几秒。然后再触发语音：我走神了。然后再继续。实际上发现这样的发呆、看门狗这些东西完全都没有必要，我需要的就是女仆锁住了一个目标之后，就持续往那边推进。咬住不放。不要再设额外的间隔之类的，同时再更改手册的配置项目。」
> 上一轮：`rounds/R926-aircraft-approach-and-mine-relocate.md`（九百二十六 补了"进场段"，本轮把它里面**那条死路**修掉）。

## 排班

- **本轮主项**：两个独立现象，一次改完、一次部署。功能面（数值/矿表/建筑/对话/战斗）一律不动；版本号仍 **1.3.2**。
- 步骤与完成判据（每件都先「定位到具体代码 → 改 → 编译护栏打包装机」）：
  1. 降落先回答"上一轮补的进场段为什么还是落不下去"，判据：能指出**具体表达式**并说明它为何永不收敛。
  2. 改 Forge 树 → 3. 镜像 Neo 树（同一份模板 + 名字映射，逐锚点断言）；判据：两树 `javac` `EXITCODE=0`。
  3. 配置项/手册/i18n/语音同步删干净 → 护栏 → 打 jar → 部署 4 实例；判据：全护栏 `FAIL 0`（`check_comment_style` 必须 `PASS`）；4 目标 `match=True`。
- 谁验收：开发者（本轮只部署、不跑回归；`## 验收` 记 `未验收`，并给出逐条实机复现步骤与日志关键字）。

## 需求 / 来源

- 现象①「飞机仍然不能具体降落」——上一轮（九百二十六）给降落补的「进场段」里，**"太近 / 已过站 / 机头朝反"那一支的瞄准点是"跟踪式"的**：它写成「她前方 90 格处的中线点」（`aimS = -Math.max(OUT_D, before + OUT_LEG)`），而 `before` 是**她到接地点的距离**——她越往外飞越大，瞄准点就**等速后退**，她**永远追不上**。实机上就是"沿跑道轴一路飞出去、一去不复返"：这不是"降落姿态不好"，是**那条链路根本不收敛**。
- 现象②「挖了一段时间之后，女仆就先发呆个几秒，然后语音：我走神了，然后再继续」——那几秒**不是引擎卡**，是**我们自己写的两段等待**：①够不着目标时的"40 拍重寻路 × 最多 3 次的阶梯"（≈6 秒站着不动）；②锁定期间**每 5 秒**从缓存里重挑一次目标（玩家说的"额外的间隔"）。阶梯走完/看门狗（8 秒）到点，会播那句「好像走神了……」。
- 开发者对②的判据很明确：**「锁住了一个目标之后就持续往那边推进、咬住不放、不要再设额外的间隔」**——所以本轮对②不是"把间隔调小"，而是**把间隔整段删掉**。

## 定位与改法

### ① 固定翼降落：三处（瞄准点 / 选跑道方向 / 没跑道时的兜底）

**定位**（`MaidMountCompat`，两树）：

1. **瞄准点永不收敛**（根因，见上）：`aircraftLandFinal` 的进场段里，
   `double aimS = (|航向差| ≤ 90° && before ≥ OUT_D) ? s + look : -(Math.max(OUT_D, before + OUT_LEG));`
   —— 后半支的 `before + OUT_LEG` 是**跟着她走**的：她每秒飞 2 格，瞄准点也每秒后退 2 格，距离**恒等于 `OUT_LEG`（90 格）**。她这辈子到不了那个点，于是永远在"飞出去"。
2. **选跑道方向不看她机头**：`aircraftLandPlan` 对每个候选点只在八个朝向里挑**条带落差最小**的那条。她当时机头朝哪、要拐多少度，**完全没进判据**——经常挑中"她背后那条"，于是她得先拐一个 180° 大弯。固定翼的转弯半径 `r ≈ v / 0.0298` 格（2.5 格/拍时 ≈ 84 格），拐这个弯本身就是"围着机场绕、越绕越远"。
3. **没规划出跑道时，非汤姆6 会"交回巡航档"**：九百二十六 只给汤姆6 加了"原地盘旋等下一轮"，别的固定翼仍然落到旧的"最平点"口径（`landingSpot`），而**那条口径的终点是 `return false`** —— 交回调用方 = 巡航档 = 掉头飞去主人那边（上一轮日志里 `固定翼巡航档 … 距目标=1009格` 一串）。

**改法**（两树，同一份逻辑）：

- **瞄准点改成"跑道远端的固定一点"**：`-(AIRCRAFT_LAND_OUT_D + AIRCRAFT_LAND_OUT_LEG)`（= 接地点外 180 格的中线点），与她当前位置**无关**。飞到那儿还没对准，就在那儿拐弯掉头（机头自然翻到 ~180°）重新朝场里飞；进了进近区（`before ≥ OUT_D`）且机头朝里（`|航向差| ≤ 90°`）就交回原有的中线追踪（`s + look`）。固定点一定飞得到，**收敛性由几何保证**。
- **顺着头选跑道**：新增常量 `AIRCRAFT_LAND_ALIGN_DEG = 45.0`；`aircraftLandPlan` 的方向挑选改成**两遍**——第一遍只在"与她当前机头相差 ≤ 45°"的朝向里挑（挑到就直接用，不拐大弯）；这一侧真过不去（条带落差超标 / 刚复飞被拉黑）才放宽到全部八向再来一遍（原行为完整保留）。`headingNow` 在候选点循环外读一次。
- **没跑道一律原地盘旋**：把汤姆6 那条 `holdAircraftPattern` 兜底**推广到全部固定翼**（这个方法本身已经由 `isFixedWingEngine` 闸住），返回值一律 `true`（本拍仍由降落链路接管）。随之**删掉旧"最平点"整段**（不再有"一个字节不变"的退回口径）：`landingSpot(...)` 方法、`AIRCRAFT_LAND_SPOT` / `AIRCRAFT_LAND_SPOT_AT` 两张表、`AIRCRAFT_LAND_RETRY_TICKS`，以及两处清缓存里的对应两行；心跳注释、`resetAircraftLanding` / `endAircraftLanding` 的说明与 `RideBindManager` 里那段"落点缓存每拍被清"的注释同步改口径（说的是 `aircraftLandPlan`，不再提 `landingSpot`）。

### ② 挖矿/伐木：把"发呆 + 间隔 + 语音"整段删掉，改成"咬住不放"

**定位**（`MaidWorkBehavior` / `MaidMineBehavior` / `MaidWoodBehavior`，两树）：

- **那几秒发呆**：`MaidMineBehavior` / `MaidWoodBehavior` 的接近段里，够不着目标时会走一条"重寻路阶梯"——40 拍等一次、最多 3 次（≈6 秒站着不动），阶梯走完仍够不着才弃置。
- **那句「我走神了」**：看门狗到点后的 `hardResetStuck` 里 `maid.getChatBubbleManager().addTextChatBubble("好像走神了……我重新理一下思路，继续干活！")`。
- **额外的间隔**：`MINE_REPICK` / `WOOD_REPICK_INTERVAL`——锁定期间每 100 拍（5 秒）从缓存里廉价重评估一次、换更优的矿（这条本身没错，但和"锁住就推进"的取向冲突，且是玩家点名的"额外的间隔"）。
- **看门狗本体**：`MaidWorkBehavior` 的 `LAST_PROGRESS` / `WATCH_SAMPLE` / `RESET_REPORT_SINCE` 三张表 + `markProgress(...)` / `watchSample(...)`，两行为里的 `stuckReset(...)` / `hardResetStuck(...)`，以及五个配置项 `mine.repick` / `mine.stuckWatchdog` / `mine.stuckResetSeconds` / `wood.stuckWatchdog` / `wood.stuckResetSeconds` 与 `ProMaidMod.runConfigMigration()` 里两条"老档归零"迁移。

**改法**（两树，矿/木同口径）：

- **删掉全部"等待"**：看门狗三表 + `markProgress` / `watchSample` / `stuckReset` / `hardResetStuck` / 那句语音、两个 5 秒重挑块、五个配置项与迁移块，一并删除（`MaidWorkBehavior` 的 `repickCooldown` 字段也删）。
- **改成"咬住不放"**：接近段够不着目标时，**每 tick 继续推进**（`approachOre` / `approachWood` 下走位、垫路指令），只是加一道**唯一**的兜底闸——**整整 40 拍（2 秒，`MINE_WEDGE_TICKS` / `WOOD_WEDGE_TICKS`）位置一动没动**，才认这块/这棵真走不到：当场弃掉（矿走 `abandon`，木记 30 秒短排）、清目标与破坏进度、`scanCooldown = 0` 让**下一 tick 立刻重跑检索链路**换下一个目标。判据用的是"她自己脚下的格子变没变"（`lastStuckPos` / `stuckTicks` / `stuckTarget`），不是计时器。
- **手册/配置同步**：两树 `GuideChaptersWork` 的挖矿、伐木两条"发呆看门狗"整条删掉；`maid_smart/lang/{zh_cn,en_us}.json` 删五条 label；`assets/promaid/i18n/en_us.json` 删对应四条译文（两条手册 + 那句语音）；语音包 `manifest.json` 删掉"好像走神了…"→`work_distracted.ogg` 这条映射（**`.ogg` 文件保留**，不动已发布产物）；`README.md` / `PUBLISH_GUIDE.md` 里那句"发呆看门狗"删掉；`DEBT.md` 的 TD-11 里点到的 `mine|wood.stuckWatchdog` 加一句"已在实测九百二十七连同整段删除"的注明。

## 触及文件

- `promaid_src` / `promaid_src_neo` × `com/maidsmart/combat/MaidMountCompat.java`（① 瞄准点固定 / `AIRCRAFT_LAND_ALIGN_DEG` 两遍挑方向 / 没跑道一律盘旋 + 删"最平点"整段）
- `…/combat/RideBindManager.java`（① 那段缓存注释改口径）
- `…/task/MaidMineBehavior.java`、`…/task/MaidWoodBehavior.java`（② 去阶梯、去重挑、咬住不放）
- `…/task/MaidWorkBehavior.java`（② 去看门狗三表 / `markProgress` / `watchSample` / `repickCooldown`）
- `…/config/MaidSmartConfig.java`、`…/ProMaidMod.java`（② 五只开关 + 两条迁移）
- `…/guide/GuideChaptersWork.java`、`assets/promaid/i18n/en_us.json`、`assets/maid_smart/lang/{zh_cn,en_us}.json`、`assets/promaid/voice/manifest.json`（② 文案/语音）
- `tools/verify/check_preset.py`（② DIY 基线 175/179 → 172/176；`GATES_ELSEWHERE` 去掉 `MINE_REPICK`）
- `ARCHITECTURE-SWITCHES.md`（② 重新生成，`gen_switch_inventory.py --write`）
- `README.md`、`PUBLISH_GUIDE.md`、`DEBT.md`（② 文案）
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行 sha256 + 第六十六次同号重打）
- `rounds/R927-aircraft-landing-fixes-and-mine-hold-on.md`（本文件）

## 验证

- **两树编译**：`compile_promaid.bat` → `EXITCODE=0`（86 个警告全是既有的 `ResourceLocation` 过时提示，与本轮无关）；`compile_neo.bat` → `EXITCODE=0`。
- **镜像自查（本轮抓到并修掉一次真问题）**：第一遍 Neo 编译报 **6 个 error**——改法脚本是"同一份文字写两树"，我新增的代码里混进了 Forge 的 SRG 名（`mount.m_20186_()` / `m_146908_()`、`maid.m_20183_()`、`maid.m_19879_()`、`targetPos.m_7949_()`），在 Neo 树里都该是 `getY()` / `getYRot()` / `blockPosition()` / `getId()` / `immutable()`。逐条改回后 `EXITCODE=0`（其中 `RECENT_DISCARD` 的键是 `Map<BlockPos,Long>`，Neo 侧既有写法用的是 `p.immutable()`，已照它对齐）。
- **全护栏**：`check_comment_style` **PASS**（931 文件 / 0 处）；`check_arch` / `check_catch_swallow` / `check_comment_dup` / `check_dev_voice` / `check_i18n_keys`（两树 0/0，config 键 621/627）/ `check_panel_coverage` / `check_preset`（DIY 关闭 **172/176**，与改后的基线一致）/ `check_reflection` **FAIL 0 / WARN 0**；`check_workflow` **FAIL 0 / WARN 3**（历史遗留：PUBLISH_GUIDE 版本、1.2.4 归档哈希、13 个更早 jar 未登记——与本轮无关）。`check_tree_mirror`（只读、不作闸门）照旧。
- **两个 jar**：forge **17580390** 字节（sha256 `913B3AE6…DCC16D`）；neoforge **17616805** 字节（`C7F9E53B…A2A222`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201）；旧包备份在 `patched/backup_old/`。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行 sha256 更新为新字节，并追加「第六十六次同号重打」；R926 旧字节 `4276FC2F…C5D75F`（forge）/ `8E6D1662…408B90`（neo）作废。
- **未跑实机回归**（先停在这，等开发者指挥）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
  - ① **固定翼降落**：让她开卓越前线固定翼（Tom F6F / AC-130H / 汤姆6 那一类）飞起来 → 点 home 模式。**应当一路走完**：日志按顺序出现 `固定翼降落·进场（…还没对准 → 飞出去重新对正）`（或 `固定翼降落·盘旋（…绕圈等…）`）→ `固定翼降落·进近` → `固定翼降落·接地段 离地=…`（逐降到 ≤6 格）→ `固定翼降落完成：已接地 → 停住不动`。**关键判据**：`进场` 日志里 `距接地点` 这一列应当**一路增大到 ~180 格然后不再增大**（旧版是"永远追不上"，会一直大下去）；随后应出现 `进近` 并落到 `已接地`。**不该再出现**"`固定翼巡航档 … 距目标=1000+格` 一路往外飞"。
  - ② **挖矿/伐木不再发呆、不再有间隔**：给她挖矿/伐木任务，锁上一块矿/一棵树。**应当一路推进**（走位、垫路、开挖），日志里**不该再出现** `mine stuck-reset: … idle=…`（看门狗已删），**不该再听到**「好像走神了……」那句语音，挖矿期间**也不该再看到**"每 5 秒重挑一次目标"那种换目标的停顿。唯一的例外情形：她真走不到（比如被围死/目标在封闭体内），40 拍（2 秒）后会换下一块/下一棵——这在日志上表现为 `mine chain burst` 之类的挖掘行**直接接着下一条目标**，中间**没有**"站着不动几秒"的窗口。
  - 判定：逐条按上面现象观察即可。若某条仍复现，请贴 `logs/promaid.log` / `logs/latest.log` 里对应该条的几行（含时间戳）。

## 边界（没动什么）

- **没动**：条带选址与条带判据（`stripRelief` / `AIRCRAFT_LAND_STRIP_*`）、下滑道斜率与距离上限（`GLIDE_SLOPE 0.10` / `GLIDE_MAX_D 90`）、接地段与"又到点又到底"那道高度闸（R924）、进场段的高度闸与盘旋降高（R926 的 `PATTERN_ALT/TOL/LIMIT`、`HOLD_R`）、跑道钉住整场的时长与漂移作废（`PLAN_KEEP_TICKS 6000` / `PLAN_KEEP_D 200`）、起飞档与"起飞被拦"判据；挖矿的锚点迁移（`slideAnchor` 8 向轮转，R926）、`purgeStaleMaids` 的存活清理、`MINE_REQUIRE_PICK`（没镐不启动）、`MINE_HARD_BLOCK_REPORT`（硬挡路报点弃置）、30 秒短排 `RECENT_DISCARD`、目标超时时钟、`TARGET_SINCE` 全都不变。
- **口径变更（有意，非回归）**：① 进场段的"飞出去重建距离"瞄准点由**跟踪式（等速后退）**改为**跑道远端的固定点**（`接地点外 180 格`）；② 选跑道方向由**只按落差**改为**先顺机头（±45°）再放宽到八向**；③ 没规划出跑道时**所有固定翼**都原地盘旋（先前只有汤姆6），旧的"最平点"退回口径**整段删除**（`landingSpot` 等）；④ 挖矿/伐木的"发呆看门狗（含 8 秒重置与那句语音）"与"锁定期间每 5 秒重挑"**整段删除**，换成"咬住不放 + 整整 2 秒没动才当场换目标"。
- **没新增开关**；**删掉 5 只开关**（`mine.repick` / `mine.stuckWatchdog` / `mine.stuckResetSeconds` / `wood.stuckWatchdog` / `wood.stuckResetSeconds`，含老档迁移块），DIY 关闭条数基线 175/179 → **172/176**；版本号仍 **1.3.2**（`WORKFLOW.md` §5「验收前不升号」）。新增数值常量只有 `AIRCRAFT_LAND_ALIGN_DEG = 45`（模块内常量，未经实机标定——如果实机看下来"她宁可拐小弯也不选更平的跑道"太明显，下一轮把这个角调小即可）；`MINE_WEDGE_TICKS` / `WOOD_WEDGE_TICKS = 40`（2 秒）由原来的重挑间隔常量改名而来，语义已换成"走不到"判据。
- 语音包只删了 `manifest.json` 里那条映射，**`work_distracted.ogg` 文件保留**（不动已发布产物的物理文件）。
- 临时脚本（`tools/_r927_*.py`、`tools/_check_*.py`、`tools/_look*.py`、`tools/_ledger*.py`、`tools/_guard_read.py`、`tools/_errs.py`、`tools/_chk_rr*.py`、`_build*.log`、`_deploy.log`、`_guard.txt`、`_g1/_g2.txt`、`_diag_out.txt`、`_dump*.txt`）**已删**；仓库根目录既有的 `_*` 文件与预置目录未动。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第六十六次同号重打。
- `DEBT.md`：不新增条目——两件都是"把已有的死路/等待改成正确行为"，不引入新的未验证功能面；TD-11 里那两只开关加了一行"已删除"的注明。
