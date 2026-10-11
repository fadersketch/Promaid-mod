# 实测八百六十三 · 飞行载具「引擎起、车不动」的启动时间

## 需求 / 来源

开发者指令：

> 酒狐怎么一绑定飞行载具（如飞机）就直接起飞 不让玩家上飞机。我觉得绑到飞机上以后，无论起飞还是接敌，
> 都要先有三秒钟左右的启动时间（飞机启动，但不移动），让玩家有机会上飞机。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树逐字镜像、临时脚本用完即删。

## 评估

骑乘指挥棒把酒狐绑上一架**飞行载具**（卓越前线的飞机/直升机这类，判据 `MaidMountCompat.isFlyingVehicle`）后，
`RideBindManager.drive()` 当拍就接管驾驶：`markTick` → `tickAttack` → 按档喂走位（接敌/跟随主人）。
玩家此刻**还没上机**，于是看到「她一绑上就自己起飞」。她要接敌时同理——绑定当拍就把机头朝目标推走。

需求是给这一类**留一段启动窗口**：引擎已经"起"（她已在驾驶位、可攻击），但**本模组在这段时间内不喂任何移动**，
好让玩家来得及走到飞机旁坐进驾驶位（坐进驾驶位后另有「驾驶让给玩家」那条分支接管，见 `playerHoldsDriverSeat`）。

口径：
- 只针对**飞行载具**（飞机/直升机）。地面坐骑（马/驴……）绑上不会"自己跑"，无此需求，不掺。
- 默认 **3.0 秒**，上限 10 秒，**0 = 关闭**（回旧行为：绑上即驱动）——即"新开关可单独关"。
- 启动窗口内**起飞与接敌都压后**（需求原话），窗口过后一切照旧。
- 用「清空输入」而不是「原地悬停」来"不动"：固定翼停不住旋翼那种悬停（没有竖直轴），`stop` 是最稳的"车不动"。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 设计 | 定修法与开关：`ride.startupDelaySeconds`（默认 3.0、随 DIY 归 0、0=关）；只管飞行载具 | 本文 §改法 | ☑ |
| 2 | 实现 | 两树 `MaidSmartConfig`：+1 声明 +1 定义（ride 组，`defineInRange` 0~10） | 两树 | ☑ |
| 3 | 实现 | 两树 `RideBindManager`：`STARTUP_UNTIL` 窗口表；`bind()` 记窗口、`drive()` 窗口内 `stop(mount)` 后 return、`releaseMaidImpl()` 清窗口 | 两树 | ☑ |
| 4 | 实现 | 两树 `MaidConfigPreset.VALUES`：`ride.startupDelaySeconds → 0.0`（DIY = 不介入） | 两树 | ☑ |
| 5 | 实现 | 两树 lang：+1 配置翻译 key（`config.promaid.ride.startupDelaySeconds`） | 4 文件 | ☑ |
| 6 | 护栏 | `check_preset`：`GATES_ELSEWHERE` +1（`COMBAT_RIDE_STARTUP_SECONDS`）；重生成 `ARCHITECTURE-SWITCHES.md` | 2 文件 | ☑ |
| 7 | 验证 | 两树编译、四护栏问题数不增、打包自检、镜像/符号比对 | 构建输出 | ☑ |
| 8 | 留痕 | `rounds/RELEASES.tsv` 同号重打留痕 + 更新本文 §验证 | 两处 | ☑ |
| 9 | 验收 | 交开发者实测（绑飞机后先有 ~3 秒不动、够时间上机；调 0 即回旧行为） | 待开闸 | ☐ |

## 改法

1. **新开关** `ride.startupDelaySeconds`（字段 `COMBAT_RIDE_STARTUP_SECONDS`，ride 组，`DoubleValue`，默认 `3.0`、
   `defineInRange 0.0~10.0`；`0` = 关闭 → 回旧行为）：
   「绑定飞行载具后的启动时间（秒，默认 3.0，0 = 关闭）：她骑上飞机/直升机这类**飞行载具**后，先有这一段
   「引擎起、车不动」的时间——本模组这段时间内不喂任何移动（**起飞与接敌都压后**），让玩家来得及上机。
   3.0 左右即够；调 0 = 立即可起飞（旧行为）」。
2. **代码**（`RideBindManager`）：
   - 静态表 `STARTUP_UNTIL`（`UUID → 解禁刻`，与 `PLAYER_DRIVING` 同排）。
   - `bind()` 里写表：`startupSecs > 0 且 MaidMountCompat.isFlyingVehicle(mount)` 时
     `STARTUP_UNTIL.put(maid, 当前刻 + (long)(startupSecs*20.0))`。
   - `drive()` 里在「玩家坐主驾 → 让位」那条分支**之后**取表：
     - **还在窗口内** → `MaidMountCompat.stop(mount); return;`（起降/接敌都压后）；
     - **过了窗口** → `STARTUP_UNTIL.remove(...)`，本拍及以后照旧驱动。
   - `releaseMaidImpl()` 里 `STARTUP_UNTIL.remove(maid)`（解绑即清窗，不留脏）。
   - **顺序要点**：窗口判断在 `playerHoldsDriverSeat` 分支**之后**，所以玩家一旦坐进驾驶位 → 走让位分支立刻接管，
     **不受窗口约束**——玩家早到不被罚等。
3. 护栏 `GATES_ELSEWHERE` +1；开关清单重生成；不改版本号、不写 changelog。

### 不做

- 不给地面坐骑/龙/木船这类加窗口（它们绑上不"自己跑"，无此需求）。
- 不改"让位给玩家"那条分支的语义；不动空袭/扫帚那两条链路。

## 触及文件

**改动**（两树各一份）：`com/maidsmart/config/MaidSmartConfig.java`、`com/maidsmart/combat/RideBindManager.java`、
`com/maidsmart/config/MaidConfigPreset.java`、`assets/maid_smart/lang/{zh_cn,en_us}.json`。
**工具**：`tools/verify/check_preset.py`、`ARCHITECTURE-SWITCHES.md`（生成物）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0` |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge 17,570,016 B（`874422bf…186eea`）、Neo 17,608,996 B（`7e1b6be9…0dc3f1`） |
| 护栏 | `check_preset` FAIL 0 / WARN 2（配置项 600/604、DIY 关闭条数 171/175，与新基线一致；WARN 2 系既有 `broom.enable` 重路径）；`check_workflow` FAIL 0 / WARN 3；`check_comment_style` 2262（均与基线同） |
| 镜像/符号 | `COMBAT_RIDE_STARTUP_SECONDS` forge=3/neo=3、`STARTUP_UNTIL` 5/5、`ride.startupDelaySeconds` 4/4、`config.promaid.ride.startupDelaySeconds` 3/3；两个 jar 的 `RideBindManager.class` 含 `STARTUP_UNTIL`、`MaidSmartConfig.class` 含该字段 |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |

## 版本与上传

- 版本保持 **1.3.2**；**不**部署、**不**跑回归、**不**发 release；同号重打按 WORKFLOW §5.2 留痕。

## 验收

**未验收**。

## 边界

- 窗口只压"本模组喂的移动"；引擎姿态、武器（`tickAttack`）、装弹不受影响——她仍然"能开火"。
- 玩家早到：坐进驾驶位那条分支排在窗口判断之前 → 立刻接管，不吃窗口。
- 关掉（`= 0`）= 完全回旧行为（绑上即起飞），便于对照。
- 窗口按"解禁刻"记在内存表里；解绑即清；重启后链路重建走原路径（无残留）。

## 台账

- 无新增结构债。TD-11 相关：新开关为 `DoubleValue`（数值即介入型），已纳入 `MaidConfigPreset.VALUES`
  → DIY 归 `0.0`（不介入），并进 `check_preset` 的 `GATES_ELSEWHERE`。DIY 布尔关闭条数不变（171/175）。
