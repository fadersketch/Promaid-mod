# 实测九百四十一 挖矿"卡在原地"（停滞脱困）

> 跑道：开发者单点反馈。上一轮：`rounds/R940-fixed-wing-cruise-speed-and-schedule-sit-riding.md`。
> 本轮依据：开发者本次实测（NeoForge_21.1.250 实例 `latest.log`）+ 代码逐行定位。

## 排班

- 单点收口；功能面（数值 / 矿表 / 建筑 / 对话 / 战斗）不动；版本号仍 **1.3.2**。
- 步骤：改 Forge → 镜像 Neo（同模板 + 名字映射，逐锚点断言唯一，命中数 ≠ 1 即中止）→ 两树 `javac EXITCODE=0` → 全护栏 `FAIL 0` → 打 jar → 部署 4 实例 `match=True`。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

开发者原话：「17:34，女仆挖矿又卡在了原地。」

## 现场（日志实证）

NeoForge 实例 `logs/latest.log` 尾部（游戏 17:34:43 存档暂停、17:34:47 退出）：

```
17:34:12.227 mine chain empty: … block=minecraft:coal_ore (no adjacent ore)
17:34:13.027 work exit: … reason=mine 朝挡路块搭高 blocker=(-1510,89,-814) feet=(-1512,85,-812)
17:34:13.231 work exit: … reason=mine 头顶压住挖开 cap=(-1512,87,-812) feet=(-1512,85,-812)
17:34:13.784 work exit: … reason=mine 朝挡路块推进(够不着矿) blocker=(-1510,89,-814) target=(-1509,90,-814)
17:34:15.478 work exit: … reason=mine 挡路块走不到→改走接近链 blocker=(-1510,89,-814) target=(-1509,90,-814)
17:34:17.680 work walk stuck: target=(-1509,90,-814) stand=(-1509,88,-814) feet=(-1511,86,-812) dist=3 path=false -> stop+retarget
17:34:22.688 work walk stuck: ……（同上，逐字相同）
17:34:27.677 work walk stuck: ……
17:34:32.081 work walk stuck: ……
17:34:37.076 work walk stuck: ……
17:34:42.079 work walk stuck: ……
17:34:43.017 Saving and pausing game...
```

**25 秒以上的稳定死循环**：同一目标 `(-1509,90,-814)`、双脚逐字不动 `(-1511,86,-812)`、
走近目标 `(-1509,88,-814)` 的 `path=false`（导航给不出路径），
`mine 够不着-继续推进` 与 `work walk stuck … stop+retarget` 交替刷屏，直到退出游戏。
期间（17:34:31）**全量扫描 `found=997 best=(-1509,90,-814)` 仍把它选为最优**——没有任何机制把它排除。

## 根因

1. **挖矿侧没有任何"够不着就放弃"的退路。** `TARGET_SINCE`（目标锁定时刻）在挖矿里被写 9 处、
   **读 0 处**；对比伐木侧 `MaidWoodBehavior:612` 是**读**出来判 `WOOD_TARGET_TIMEOUT` 超时弃置的。
   九百三十二 又把挖矿原来的"2 秒没挪窝就弃置 / 连弃 3 次换框"整段删除（口径：她有镐有方块、
   不存在够不着，推进链必须自己送她上去）。
2. 实机里该口径的前提不成立：**「站位在矿下方 2 格、头顶那格刨不动、导航又给不出路径」三件事
   同时成立时**，`approachOre` 的三个出口全落空——
   - 垫高（`pillarUpStep`）：头顶 `place+1`/`place+2` 是会窒息的实心块 → 返回 false；
   - 挖开头顶（`digUpCap`）：头顶那块不在可挖名单（`isOpenStone`=false）→ 返回 false；
   - 走位（`walkToOreBase`）：`findStandNearOre` 只给"矿下方 2 格 / 同层 / 上方"的站位（最低 y=矿y−2），
     她要从 y=86 上到 y=88 才能站，导航无路 → `path=false`。
3. `reportWalkStuck` 打印的 `-> stop+retarget` 是**名义**：它只写日志（+ 停一次导航），
   随后 `setWalkTarget` 又把**同一个够不到的站位**重发一遍 → 死循环永不脱离。

## 改法（收敛为"真的没挪窝才认"）

按**伐木同款**给挖矿补回"停滞检测"，判据收紧为**同一目标 + 双脚位置连续 `MINE_WEDGE_TICKS`(40) 拍
一字未动**（她只要在推进——垫高 / 走位 / 开挖——双脚就一直在变，不会被误弃）：

- `MaidMineBehavior` 新增字段 `MINE_WEDGE_TICKS` / `lastStuckPos` / `stuckTicks` / `stuckTarget` /
  `wedgeStreak` / `lastWedgeAt`；
- `distSq > reachSq()`（"够不着"）分支**进入推进之前**先跑停滞检测：连续 40 拍双脚没动 →
  `giveUpLog("够不着且没挪窝")` + 目标进 `RECENT_DISCARD`（30 秒短排）+ 清目标 / 进度 /
  `lastWalkTarget` / `scanCooldown=0`（下一拍立刻重扫）；
- **连弃 3 次**（200 拍内）→ 清 `ORE_CACHE` + `MINE_SCANS` + `slideAnchor`（这一框没戏，换框）；
- 挖矿两处扫描（`fullScanOres` / `pickFromCache`）**也认 `RECENT_DISCARD`**——此前只有伐木认这张表，
  挖矿不认（否则弃置当场又被选回来）；`findOre` 加过期修剪（>600 拍，与伐木同口径）；
- `forget()` 一并清理 `RECENT_DISCARD`（与伐木共表）。

与九百三十八 的「目标跑出检索半径→清空」区别（两者都写 `RECENT_DISCARD`，但触发面不同）：
九百三十八 是**按检索半径**判、单独清空一次；本轮回的是**按"人真的卡住"**判，且记 30 秒短排
（不记就会当场重选同一块、死循环照旧）。

## 触及文件

- 两树 × `com/maidsmart/task/MaidMineBehavior.java`（字段 / 停滞检测 / 两处扫描认 `RECENT_DISCARD` / `forget`）
- `rounds/RELEASES.tsv`（第八十次同号重打）+ `rounds/R941-mining-stall-recovery.md`（本文件）

**未动**：`approachOre` 的推进策略、`pillarUpStep` / `digUpCap` / `findStandNearOre` / `walkToBlockFace`
的选址逻辑、`MaidWorkBehavior` 的走路/留痕/`RECENT_DISCARD` 本体、伐木侧任何东西、其它行为。
**没新增配置开关**（`MINE_WEDGE_TICKS` 为 mod 内部常量）；DIY 关闭条数基线仍 **172/176**；版本号仍 **1.3.2**。

## 验证

- **两树编译**：`compile_promaid.bat` / `compile_neo.bat` → `EXITCODE=0`（14 处锚点，`FAILS=0 / TOTAL=14`；
  Neo 侧 `p.m_7949_()→p.immutable()` 一处映射补正后重编通过）。
- **镜像自查**：新增/改动逐条读回两树——Forge 一律 SRG（`m_20183_` / `m_19879_` / `m_20148_` / `m_7949_`），
  Neo 一律 Mojang（`blockPosition()` / `getId()` / `getUUID()` / `immutable()`）。
- **全护栏**：11 个全 `FAIL 0`（`check_comment_style` **PASS**；`check_preset` **FAIL 0 / WARN 0**、
  DIY 基线仍 **172/176**；`check_workflow` 3 条历史 WARN；`check_tree_mirror` 只读报告）。
- **两个 jar**：forge **17588387** 字节（sha256 `76532293…777EA3`）；
  neoforge **17624757** 字节（`615ECCFC…73C503`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 行更新 sha256 并列第八十次同号重打；
  九百四十 的旧字节 `9EB8E32B…0DB179`（forge）/ `49AB5091…EB7010`（neo）作废。
- **未跑实机回归**（已部署，等开发者实测）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- **判据**：再遇"站位在矿下方 / 头顶刨不动 / 导航无路"那块够不到的矿时，她最多原地停 **2 秒**
  （40 拍）就放弃它、清进度、重新检索；连续三块都够不到则换框迁移。
  日志可见 `mine give-up: … reason=够不着且没挪窝 …`，以及（连弃 3 次时）
  `mine box unreachable (3 连弃置、零破坏) → fresh box: …`。
- **副作用预期**：极少数"其实走得过去、但站位给不出路径"的目标会被她弃置、下一轮重选（代价是多一次重选）。
  这是把"永久卡死"换成"换一块"的有意取舍。

## 边界（没动什么）

- **没动**：`approachOre` 的推进策略、`pillarUpStep` / `digUpCap` / `findStandNearOre` / `walkToBlockFace`
  的选址逻辑、`MaidWorkBehavior` 的走路 / 留痕 / `RECENT_DISCARD` 本体、伐木侧任何东西，
  以及挖矿 / 伐木的扫描排序（仍"距她最近优先"）。
- **口径变更（有意，非回归）**：挖矿侧"够不着"时从"永久咬住不放"改为"同一目标 + 双脚连续 40 拍
  没动 → 弃置该矿、记 30 秒短排、重扫；连弃 3 次换框"；挖矿两处扫描开始认 `RECENT_DISCARD`
  （此前只有伐木认）。
- **没新增配置开关**（`MINE_WEDGE_TICKS` 为 mod 内部常量）；DIY 关闭条数基线仍 **172/176**；版本号仍 **1.3.2**。
- **已知取舍（如实记录）**：判据是"双脚没动"——她若在推进（垫高 / 走位 / 开挖）就不会被弃；
  但"其实走得过去、只是站位给不出路径"的目标也会被弃置、下一轮重选，代价是多一次重选，
  换取"永不永久卡死"。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第八十次同号重打。
- `DEBT.md`：不新增条目——本轮是"把挖矿侧缺失的脱困退路按伐木同款补齐"，不引入新的未验证功能面。
