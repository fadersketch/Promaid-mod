# R998 伐木清叶工作闸复核 + 挖矿静默链路「玩家介入即止」 + 四任务 home 工作区钳制

> 上一轮：`rounds/R997-schedule-teleport-direct-and-diamond-sword-forensics.md`（传送直达坐标，1.3.3 同号重打，**未发布**）。
> 开发者原话（三条）：「1.伐木模式下有一个自动破坏树叶的功能。这个功能是必须既处于伐木状态又是工作状态，
> 还是只要是伐木模式就行了？前者才是正确的。如果不是，则改过去。休息时段就跟原版TLM一样不干活，被动
> 破坏树叶也不会触发。 2.挖矿功能有个小bug，女仆在检索不到矿的时候切换成空闲模式。这个时候如果玩家
> 将它切换到攻击模式，他还是会每隔一段时间就报找不到矿。理论上玩家强制介入切换完模式之后就应该把链路
> 终止。 3.伐木/挖矿/酿造/烧制这些我们新加入的功能状态下的女仆如果是处于home模式，检索范围会被强制
> 限制只在home/河童的罗盘画的那个工作区域里面。超出的一概不管（这是对齐原版口径）」

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 复核（①：清叶到底吃哪几道闸——用 javap 当证据判"是否要改"）＋ B 修 bug（②：无目标静默链路
  在玩家强制切模式后要整条终止）＋ C 口径对齐（③：四任务 home 工作区钳制补齐）。
- **开工前读过**：
  - `rounds/RELEASES.tsv`：1.3.3 两条 `workspace`（R997，**第一百三十三次**）→ 本轮**第一百三十四次同号重打**
    （前一次字节作废）。
  - `ARCHITECTURE-SWITCHES.md`：无新开关——②是修错、③是照既有 `WorkAreaClamp` 口径补齐（该口径本来就声明
    "home 走限制圈、非 home 恒放行"），不新增配置项。
  - `DEBT.md`：无未清 P0/P1（TD-22 与本轮无关）。
  - 上一轮记录 R997：1.3.3 未发布、本轮顺延 R998。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位（清叶闸门 / 静默链路 / 四任务检索点） | 每条结论指着 javap 字节码或 `file:line`（见「评估」） | ☑ |
| 1 | 两树改 + 编译 | ②链路终止闸；③木/矿当前目标复检 + 酿造/烧制检索与绑定双闸；两树 `javac` `EXITCODE=0` | ☑ |
| 2 | 14 护栏 | `FAIL 0 / WARN 0` | ☑ |
| 3 | 同号重打 1.3.3 + 装 4 实例 | 双 jar；`match=True`；`RELEASES.tsv` 两条新行（void 旧字节） | ☑ |
| 4 | 专服回归三件 | `test_server.py 1201` / `test_server.py neoforge1211` / `test_maid_load.py neoforge1211` 全 PASS | ☑ |
| 5 | 交开发者验收 | 实机复看三条（清单见下） | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。
  1. **清叶双闸**：白天班女仆夜里等休息时段，站在树冠里/树边——树叶**不掉**（她也不干活）；白天工作时段
     砍树时照旧清叶。②的对照：把女仆切成**空闲**任务后树叶也不掉（任务行为不在脑内）。
  2. **静默链路**：挖矿女仆在没矿的地方触发"这片区域没有可挖的矿石了"→ 约 2 秒后她自己切到空闲 →
     此时你把她切成**攻击**模式 → **不再**出现"找不到矿"的周期播报、她也不会被自己切回采矿；
     日志（latest.log）搜 `work idle-switch:` 应看到 `task changed by player ... chain dropped`。
     反例（预期不变）：她切空闲期间你把任务切成别的、再切回采矿，链路按新报告重新走。
  3. **home 工作区钳制**：开 home（河童罗盘/排班锚点画圈）后，在**圈外**放炉子/酿造台/树/矿——
     她一概不去（不选中、不绑定）；把圈挪走后再看，原先绑定在圈外的炉子/酿造台会被解绑、只找圈内的。
     她正在采的目标若因挪圈落到圈外，会被清掉重选（不抱着圈外的树/矿砍完）。
     非 home（跟随）模式的检索范围**不变**（恒放行，老手感）。
  4. 回归对照：跟随模式下烧制/酿造/挖矿/伐木的检索与之前一字不差（不限于 home 才生效）。

## 需求 / 来源

- 开发者实机三条（原文见上）。①是口径复核，②是 bug 报告，③是口径对齐要求。

## 评估

### ① 伐木清叶的现有闸门（结论：现状即"伐木 + 工作"双闸，无需改代码）

- 全树的"世界树叶破坏"只有一处实现：`MaidWoodBehavior` 的 `burstNearbyLeaves`（她身边 3×3×3，`wood.leavesClear`）
  与 `clearCanopy`（砍掉一段树干时清周围，同一开关）——全树枚举 `LeavesBlock` 后其余命中都是判定/保护/燃料
  分类，没有第二个破坏点。
- 她的行为只在**伐木任务**的大脑里：`MaidWoodTask.createBrainTasks` 返回 `MaidWoodBehavior`，由 TLM 的
  `MaidBrain.registerWorkGoals` 收进 **`Activity.f_37980_`（= "work"）**——javap 实证：整个 `MaidBrain` 里
  `IMaidTask.createBrainTasks` 只出现一次（work 组），`registerIdleGoals` / `registerRestGoals` 都不含任务行为；
  `Activity` 的 `static{}` 逐字段实证：`f_37978_="core"` / `f_37979_="idle"` / **`f_37980_="work"`** / `f_37982_="rest"`。
- 作息→活动的映射由 TLM 自己的 `MaidUpdateActivityFromSchedule` 每 tick 执行（javap：`MaidSchedule` 全天→WORK 常驻；
  早班/晚班走 `MAID_DAY/NIGHT_SHIFT_SCHEDULES` 的 `m_38019_(time)`）：**休息时段活动 = REST，work 行为列表整体不评估**
  → "被动破坏树叶"物理上不可能触发（她连 tick 都不 tick）。
- 骑乘状态同理：`MaidUpdateActivityFromSchedule` javap 里 `isPassenger` 分支把 WORK/IDLE/其余映射到
  `RIDE_WORK` / `RIDE_IDLE` / `RIDE_REST`——坐着/骑乘时，休息时段同样不评估任务行为（`createRideBrainTasks` 那份）。
- 另有本模组自己的一道闸：**无目标静默窗口**（`startIdlePause`）内，伐木 tick 一进来就清目标/还走位权/`return`
  （`MaidWoodBehavior` 静默窗口分支在清叶代码之前）——静默闲逛那 10 秒也不会顺手清叶。
- 本模组的 core / rest 注册清单里没有任何清叶代码（`ProMaidExtension.getCoreBehaviors` / `getRestBehaviors` 逐条核对）。
- **结论**：现状 = 开发者要的"前者"（伐木状态 × 工作状态双闸）。**不改代码**，只在本记录留证据。若实机仍见
  休息时段掉叶，按新证据另开——本轮不动。

### ② 静默链路为什么会"玩家切了攻击还报找不到矿"

- 机制（`MaidWorkBehavior`，实测九百七十七/九百八十二 建）：挖矿/伐木确认"这片没有目标"→ 气泡 →
  `startIdlePause` 同时 `armWorkIdleSwitch`（记下当前任务 UID）→ 40 拍后由**常驻 core 行为**
  （`MaidToolAutoEquipBehavior` 每 tick 调 `tickWorkIdleSwitch`）把任务切成 `touhou_little_maid:idle` →
  静默时长（`mine.noTargetPause`，默认 10 秒）到点后**切回原任务**（除"真在战斗 / 排班接管"两种让位）。
- 症状根因：旧版两个分支**都不检查"她是否还挂在我们排的那条线上"**——玩家把空闲中的她手切成**攻击**任务，
  但"没接敌"时 `AutoCombatSwitch.isReallyCombatActive` = false → 到点判定"可以还原"→ **把她切回采矿** →
  采矿链路重新扫描 → 又气泡"找不到矿" + 又 arm → 循环（与报告"每隔一段时间就报找不到矿"逐字吻合）。
- 修法（实测一千〇五【玩家介入即止】）：链路加"期望任务"闸——
  - 还没切空闲时：期望 = 原工作任务（`prev`）；
  - 已切空闲时：期望 = `touhou_little_maid:idle`；
  - 当前任务 ≠ 期望 → **整条作废**（MEM / PREV / `IDLE_PAUSE_UNTIL` 一并清），不切空闲、也绝不切回，
    日志留痕 `work idle-switch: ... task changed by player ... chain dropped`。
  这样"玩家强制介入切完模式"的那一刻链路即终止，不再有任何延迟动作/播报。

### ③ home 工作区钳制复核（wood/mine 已齐 + brew/cook 补缺口）

- 口径本体（`WorkAreaClamp`）：`allowsWork` = `allows` = **home 模式**（TLM 把 `hasRestriction` 重写为
  `isHomeModeEnable`）→ `isWithinRestriction(目标)`；**非 home 恒放行**。这正是"home 检索圈 / 跟随不设圈"。
- **木/矿（复核无缺口）**：连锁队列（`allowsWork` 闸）、迁移锚点（圈外时锚点落回圈心）、框空迁移
  （home 钉死、不迁移）、全量扫描与缓存取点（逐点 `allowsWork`）——四处都在。本轮**再补一处**：当前已选定的
  目标在下一次 tick 也过 `allowsWork`，圈心被玩家挪走时当场清空重选（与"跑出检索半径"同款、不进排除表）。
- **酿造/烧制（本轮补）**：两处缺口——① 检索（`findBrewingStand` / `findFurnace`）只按"她坐标 ± 半径"扫立方体，
  没有圈闸；② 绑定后的 `standPos` / `furnacePos` 只验"方块还在不在/炉型匹配"，不复检工作区。现在两处都过
  `allowsWork`：圈外候选直接跳过；已绑定目标落到圈外 → 与"方块没了"同待遇（解绑 + 边走边找圈内的）。
- 非 home 行为不变：`allowsWork` 恒 true → 跟随模式的检索与老版本逐字节等价。

## 改动（触及文件）

- `promaid_src/com/maidsmart/task/MaidWorkBehavior.java`：`tickWorkIdleSwitch` 加"期望任务"闸 +
  `prevBack` 命名（链路终止时清三张表 + 留痕）。
- `promaid_src/com/maidsmart/task/MaidWoodBehavior.java` / `MaidMineBehavior.java`：目标失效检查里补
  "目标跑出工作区 → 单独清空一次"（与检索半径那条同款，不进排除表）。
- `promaid_src/com/maidsmart/task/MaidBrewBehavior.java` / `MaidCookBehavior.java`：检索候选加
  `allowsWork` 闸 + 已绑定台子/炉子落在圈外时解绑重找。
- NEO 镜像同五文件（Mojmap：`getBlockState` / `getBlock()` / `getUUID` / `m_…` → 同名 API）。
- **触及文件：10 个**（两树 × 5）。①无需改（复核留证）。

## 验证

- 命令 + 结果：
  - 两树 `javac` **EXITCODE=0**。
  - 14 护栏**全 FAIL 0 / WARN 0**（`check_comment_style` PASS）。
  - 双 jar（**同号重打**）：`patched/promaid-1.3.3-forge-1.20.1.jar` **17918292** 字节，sha256
    `e21fc36c955d636baddea039d88a9f86c49fc99e20dc73a5e303433a456d1403`；`patched/promaid-1.3.3-
    neoforge-1.21.1.jar` **17965878** 字节，sha256 `5ed9f7d69c05a3f692170a35c9403e2fdbdb38a02cd0
    6d45a8b94e198b75fd98`。构建内 mixin 注入点审计 + lang json 校验 PASS（`build_promaid.py` 内建硬闸）。
  - **部署 4 实例：全部 `match=True`**；**专服回归三件全 PASS**（`test_server.py 1201` verdict PASS、`Done (2.897s)` /
    `test_server.py neoforge1211` verdict PASS、`Done (1.238s)` / `test_maid_load.py neoforge1211` verdict PASS）。
- **反证**（修 bug / 口径轮必填）：
  - ①"休息时段不触发"是**结构性**的，不是靠运行时判据挡的：任务行为整个不在非 work 活动的列表里
    （javap 两处：`createBrainTasks` 只进 work 组；`isPassenger` 分支映射到 RIDE_*）。旁路排查：全树唯一
    的"强制 WORK"是 `BuildActivityForceMixin`，条件写死 `build` / `index_build`，不含伐木/挖矿——不存在
    "任务行为在休息时段被我们自己唤醒"的路径。
  - ②旧版必然复发（不是偶发）：让位判据只有"真在战斗/排班"两条，玩家手切攻击而**未接敌**时两条都是 false，
    到点必然切回采矿；修复后终止条件是"任务 ≠ 期望"，与"接不接敌"无关——玩家的任何强制切换都能终止。
  - ③没误伤非 home：`allowsWork` 非 home 恒 true（口径本体 javap/TLM 实证：`hasRestriction` 被 TLM 重写为
    `isHomeModeEnable`），跟随/战斗模式的四任务检索一字未改。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）

- 状态：`未验收`（等开发者按「排班」三条清单实机复看）。
- 未验收之前：不写 changelog、不写 release 说明、不发平台。

## 边界（没动什么）

- ①**未改任何代码**：现状即"伐木任务 × 工作时段"双闸（REST 期间任务行为不被评估）。若实机仍见休息时段
  掉叶，请给出坐标/时间/当时任务与作息，我按新证据再查——本轮不动。
- ②只终止"我们自己排的无目标链路"：玩家手动切回采矿后，采矿本身照常工作（新的"无目标"周期会重新计）。
  切空闲那一下仍保留 2 秒延迟（先让主人看清气泡），这是实测九百八十二定下的手感，未动。
- ③钳制**只在 home 模式**生效（非 home 恒放行）；已绑定的炉子/酿造台落到圈外会**解绑重找**（占用表一并释放，
  别的女仆可以接手）；在采目标被挪出圈会被清空重选——"半成品不抱死"是有意的（对齐"超出的一概不管"）。
- 骑乘脑同样按作息（`RIDE_WORK` / `RIDE_IDLE` / `RIDE_REST`），休息时段连骑乘任务行为也不评估。
- 版本号仍 **1.3.3**（未发布时段同号重打，旧字节在 `RELEASES.tsv` 记废）。

## 台账

- 清掉：无既有债号（①②③ 均为本轮新报告/新复核）。
- 新增债：`DEBT.md` BD-32（无目标静默链路不认玩家介入——已清@实测一千〇五）、BD-33（home 工作区钳制在
  酿造/烧制检索与在采目标上缺闸——已清@实测一千〇五）；§0 模块索引「挖矿 / 伐木 / 农场（生产链路）」行补两项。
- 发布登记：`rounds/RELEASES.tsv` 追加 **1.3.3** 两条 `workspace`（**第一百三十四次同号重打**：本行现字节；
  前一次 `835c66ee…f999` / `d31f318f…b0a0` 作废）。
- 升号：无（1.3.3 未发布 → 同号重打）。
