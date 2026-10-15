# 实测九百三十七 挖矿「挖矿中不报没矿」＋ 水流卡住兜底 ＋ 滑翔/固定翼降落「无外飞腿 · 接地刹死」

> 跑道：**开发者点名三件**。开发者原话：「1.当前女仆对于挖矿状态下就是很容易误报周围没有矿石。理论上只要女仆进入挖矿状态就不应该出现这样的报错了。2.有的时候女仆会被水流卡住。那种时候不触发窒息，而他又动不了。而矿洞下面水跟岩浆的数量肯定是不少的，岩浆还好有自保兜底。但是水就不好说了。尤其是那种不会窒息的。会一直把女仆卡在一个位置，而无法进行工作，没有相关的优化。3.降落方面，滑翔式飞机总是在最后贴地滑行，但是没有办法完成静止，似乎还保有速度。汤姆是可以降落的，但是总是要先飞得特别远。理论上像这些滑翔类的飞机，我认为他们的降落路径应该是仍然保持原来的飞行轨迹。但是头会朝下压，并且速度会逐渐减少。接地时不再给予发动机速度。只依靠惯性降落。直升机以及其他飞机方面不需要管。」
> 上一轮：`rounds/R936-single-block-pacing-and-landing-descent.md`（九百三十六 挖矿「一个方块里来回走」＋降落「只降不抬 / 别压进土里」）。
> 本轮依据的实机日志 = 开发者本次测试（NeoForge_21.1.250 `latest.log`，14:35:11~14:49:08，**跑的就是九百三十六 的 jar**）。

## 排班

- **本轮主项**：①挖矿态仍报"附近没有值得挖的矿石"——钉死是谁在报、加闸；②水流卡住（不窒息、动不了）——补一个兜底；③滑翔/固定翼降落「无外飞腿 + 接地刹死 + 收油」，直升机/飞艇/其他一律不碰。功能面（数值/矿表/建筑/对话/战斗）不动；版本号仍 **1.3.2**。
- 步骤：改 Forge → 镜像 Neo（同一份模板 + 名字映射，逐锚点断言唯一，命中数 ≠ 1 即中止）→ 两树 `javac EXITCODE=0` → 全护栏 `FAIL 0` → 打 jar → 部署 4 实例 `match=True`。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`，并给逐条看日志的方法）。

## 需求 / 来源

三条都能在代码里找到确凿落点：

- **现象①「挖矿状态误报周围没有矿石」**：这句话出自**工作状态播报** `WorkStatusReporter.idleReason`（`return "附近没有值得挖的矿石"`，见语言文件 `en_us.json:4477` 的同名 key）。旧判据只有"**导航空闲** + 任务=挖矿 + 主手是镐"。而实机日志（14:36:19 起）里她**锁着矿、站在矿前挖/垫路**时导航也是 `isDone`（挖矿不需要导航）——于是"一边挖一边报没矿"。这就是"很容易误报"。开发者口径：进入挖矿状态就不该报。
- **现象②「被水流卡住」**：矿洞水/岩浆都多。岩浆有自保（`MaidHeatEscape` / 危险方块逃离）兜底，水**没有**——水不触发窒息（头在水外），她只是被水流顶在方块上/泡在一格水里，导航失败、原地不动、日志里也没有任何留痕。全仓搜 `isInWater` / 水域处理，行为层（`MaidWorkBehavior`）里没有一条。
- **现象③「滑翔机贴地滑行停不下来 / 汤姆总要飞特别远」**：实机日志 `latest.log` 14:46:06~14:46:19 **连续十几秒**每拍 `固定翼降落·完成 已接地 → 停住不动` —— 她"号称停住"却在贴地滑行（`stopVehicle` 对飞行档只清输入、`killHorizontal` 只按 0.88 衰减，发动机地面推力还在把她往前蹭）。而 14:47:01~14:47:12 的进场段 `距接地点` 从 22 涨到 **93** 才回头（`aimS = -(OUT_D+OUT_LEG) = -150` 的外飞腿），正是汤姆"总要飞得特别远"。

## 定位与改法

### ① 挖矿态不报"没矿"（`WorkStatusReporter.idleReason`，两树）

- 判据里加一条：`MaidMineBehavior.isMining(maid)`（挖矿行为手上锁着目标，见 `MINING` 集合）为真 → `return null`。锁定目标期间一律不播报；真正空闲（没锁定）时才照旧播报。这正是开发者要的"进入挖矿状态就不该报"。

### ② 水流卡住兜底（`MaidWorkBehavior`，两树）

- 新增 `antiWaterStuck(level, maid, gameTime)`：`maid.isInWater()` 为真、且脚下格持续 `WATER_STUCK_TICKS`(**40** 拍 = 2 秒)不变 → 判"被水流卡住"，用 `findDryStand` 搜附近（水平 ±3、垂直 -1..+2）最近一格**干燥可站**（本格与上一格都不是水、本格空气、脚下实心），把她 `setPos` 过去并留一行 `work water stuck escape`。与 `antiSuffocate` 同款"宁可瞬移半步"的最后手段。
- 接入点：`MaidMineBehavior.tick` 与 `MaidWoodBehavior.tick` 在 `antiSuffocate(maid)` 之后各接一次（挖矿/伐木两个工作行为都管）。
- 没动岩浆那一档（自保照旧）；水桶/落水缓冲等别处逻辑不动。

### ③ 滑翔/固定翼降落（`MaidMountCompat`，两树）

- **（a）删掉外飞腿**：进场段 `aimS` 由 `(|航向误差|≤90 且 已过 OUT_D) ? s+look : -(OUT_D+OUT_LEG)` 改为**一律 `s + look`**（与对准段同一纯追踪）。她不再"朝接地点外 150 格飞出去掉头"——顺着原航迹一路压机头往下（开发者口径"保持原航迹"），落点自然收在接地点一带。**汤姆不再飞得特别远**。
- **（b）接地闸放宽**：由 `before ≤ TOUCH_D && 离地 ≤ FLARE_ALT` 改为 `离地 ≤ FLARE_ALT && (before ≤ TOUCH_D || 离地 ≤ TOUCH_ALT+1)`——纯追踪盘旋下降时她可能绕在圈外一点点、永远进不了 TOUCH_D 圈，旧闸会让她吊在半空收不了尾；现在"够低"本身就够。
- **（c）接地收油 0 + 水平刹死**：接地段油门由 `AIRCRAFT_LAND_IDLE_POWER`(0.08) 改为 **0**（开发者："接地时不再给予发动机速度"）；水平速度由 `killHorizontal(..., FLARE_RETAIN=0.88)` 改为 **`killHorizontal(mount, 0.0)`**（直接抹零）。
- **（d）完成段每拍抹零**：已接地时每拍再 `killHorizontal(mount, 0.0)`——彻底终结"贴地滑行停不下来"。
- **没动**：条带选址 / 判据、`CORRIDOR` / `MAX_BEARING` / `ALIGN_DEG`、`PATTERN_ALT`、`PLAN_KEEP_TICKS`、九百三十四 的硬限速、九百三十五 的陡下滑 / 快下沉、九百三十六 的"只降不抬 / 地面净空 / 清向下速度"；**直升机（HELICOPTER）、飞艇（AIRSHIP）与其他非固定翼一律不碰**（改动都在 `aircraftLandFinal` / `aircraftHomeLanding`，只服务 `isFixedWingEngine`）。

## 触及文件

- `promaid_src` / `promaid_src_neo` × `com/maidsmart/dialogue/WorkStatusReporter.java`（①）
- `…/task/MaidWorkBehavior.java`（② 方法 + 字段）+ `…/task/MaidMineBehavior.java` / `MaidWoodBehavior.java`（② 接入）
- `…/combat/MaidMountCompat.java`（③ 进场 aimS / 接地闸 / 接地收油刹死 / 完成段抹零）
- `rounds/RELEASES.tsv`（第七十六次同号重打）+ `rounds/R937-mining-status-water-escape-and-glider-landing.md`（本文件）

## 验证

- **两树编译**：`compile_promaid.bat` / `compile_neo.bat` → `EXITCODE=0`。**首轮 Forge 编译失败 1 处**——② 的日志调用把 Neo 的 `maid.getUUID()` 带进了 Forge 树（应为 `m_20148_()`），改正后重新编译 `EXITCODE=0`。
- **护栏首轮 FAIL 1 处**：`check_comment_style` 报 `LONG-BLOCK` 4 处（我新增/紧邻的注释串成 12 / 10 行 > `MEMBER_MAX`=8）。修法：删掉九百三十五 那段已过时的"飞出去重建距离"描述（外飞腿已删，注释也该走）、把接地闸新注释压成 1 行 → 复查 `check_comment_style` **PASS**（931 文件 / 0 处）。
- **全护栏**：11 个全 `FAIL 0`（`check_preset` **FAIL 0 / WARN 0**、DIY 基线仍 **172/176**——本轮不增删开关；`check_workflow` 3 条历史 WARN）。`check_arch` 一致差 0。
- **两个 jar**：forge **17586024** 字节（sha256 `6ECA0974…A6618F`）；neoforge **17622352** 字节（`3C7FE7A9…A9D87B`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行 sha256 更新为新字节，并追加「第七十六次同号重打」；九百三十六 的旧字节 `96AB4302…77BB94`（forge）/ `A2125475…8FE0D5`（neo）作废。
- **未跑实机回归**（已部署，等开发者实测）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- ① **挖矿不再报"没矿"**：给她挖矿任务。**判据**：她**锁着矿在挖/垫路期间**不该再出现气泡/聊天"附近没有值得挖的矿石～"；只有她**真的没锁定目标**时才会报。
- ② **水流卡住能自救**：找一处会顶人的水流让她进去。**判据**：卡住约 2 秒后日志应出现 `work water stuck escape: maid=… from=… to=…`，她应被挪到附近干燥可站的格、继续干活。若她仍在原地不动且无该日志，请贴当时前后 20 行回来。
- ③ **滑翔/固定翼降落**：让她开滑翔翼 / 汤姆6 进 home 档。**判据**：
  1. `固定翼降落·进场` 的 `距接地点` 不该再"一路飞出去又回来"（九百三十六 那次 22→93→回来 的形状不该再现），应当顺航迹收拢；
  2. 接地后**完全停住**：`接地段` 之后不应再有十几秒"完成"却仍在滑行；落地即静止；
  3. `接地段` 附近应能看到她收油门（不再有怠速推力）、`水平速度` 迅速归零。
  - 直升机 / 飞艇 / 其他载具的降落手感应与之前**完全一致**（本轮未碰）。
- 判定：逐条按上面现象观察即可。

## 边界（没动什么）

- **没动**：降落链路条带选址 / 判据、`CORRIDOR`(14) / `MAX_BEARING`(40) / `ALIGN_DEG`(45)、`PATTERN_ALT`(24)、`PLAN_KEEP_TICKS`(6000)、`FLARE_ALT`(6) / `TOUCH_D`(12) / `TOUCH_ALT`(1.2)、九百三十四 的 `AIRCRAFT_LAND_SPEED_STEP` / `aircraftGovernSpeedHard`、九百三十五 的 `GLIDE_SLOPE`(0.18) / `DESCENT_VY_MAX`(0.30)、九百三十六 的"只降不抬 / 地面净空 / 清向下速度"；直升机 / 飞艇 / 其他载具全档不动。
- **没动**：挖矿的 `approachOre` / `pillarUpStep` / `digUpCap` / `walkToOreBase` / `findStandNearOre` / `walkToBlockFace` / `unreachableBlocker` 记忆（九百三十六）、`blockedOres`、`slideAnchor`；`WorkStatusReporter` 的其它任务分支（建造/烹饪/酿造的静默口径照旧）；岩浆自保（`MaidHeatEscape` / 危险方块）照旧。
- **口径变更（有意，非回归）**：① 挖矿态（锁定目标）不播报"附近没有值得挖的矿石"；② 水里卡 2 秒 → 挪到附近干燥可站格；③ 进场删外飞腿（一律纯追踪接地点前方）、接地入场放宽到"够低即落"、接地油门归零 + 水平抹零、完成段每拍抹零。
- **没新增开关**；DIY 关闭条数基线仍 **172/176**；版本号仍 **1.3.2**（`WORKFLOW.md` §5「验收前不升号」）。新增常量：`WATER_STUCK_TICKS`（mod 内部，不入配置）。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第七十六次同号重打。
- `DEBT.md`：不新增条目——三条都是"按开发者口径把现有行为收口"（状态播报加闸 / 水流兜底 / 降落收口），不引入新的未验证功能面。
