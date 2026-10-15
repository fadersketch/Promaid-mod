# 实测九百三十八 伐木/挖矿七项收口（路径岩浆播报 · 出圈清目标 · 抗火豁免 · 避险前瞻 · 没斧提示 · 船免电 · 接地渐减速）

> 跑道：**开发者点名七件**（原话见下）。上一轮：`rounds/R937-mining-status-water-escape-and-glider-landing.md`。
> 本轮依据：开发者本次实测反馈（NeoForge_21.1.250，跑的是九百三十七 的 jar）+ 代码逐项定位。

## 排班

- 七件一次收口；功能面（数值/矿表/建筑/对话/战斗）不动；版本号仍 **1.3.2**。
- 步骤：改 Forge → 镜像 Neo（同模板 + 名字映射，逐锚点断言唯一，命中数 ≠ 1 即中止）→ 两树 `javac EXITCODE=0` → 全护栏 `FAIL 0` → 打 jar → 部署 4 实例 `match=True`。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

开发者原话：「1.女仆在发现伐木/挖矿目标路径上有岩浆时应该汇报一下。2.伐木与挖矿时女仆发现自己的目标在检索判定范围之外的时候，则取消这个目标。重新执行一次链路（即：女仆默认会检索24格半径是否有矿物，如果突然发现自己目标的矿物在24格外，则放弃，清空这个目标，不额外记忆，只是单独清空一次。）3.女仆有关岩浆/燃烧/岩浆块方面的自救在自身存在抗火效果/火焰保护饰品时不生效 4.女仆开飞机的时候只死读了自己距离主人的高度。但是完全不考虑路径上是否有障碍物。女仆在乘坐扫帚的时候还会自然调整高度，但是她在乘坐其他飞机的时候不会。理论上发现眼前有障碍物的时候，应该是尝试抬升高度来避开障碍物。前方没有障碍物之后再尝试回落到原来的高度。跟扫帚一样。5.女仆在伐木时报自己没有工具的语音过于频繁。每次进入伐木模式顶多说一次。6.汤姆和快艇理论上连电都不需要就能起飞的。7.接地时立刻解除速度似乎还是不太好，应该是让速度逐渐降为零，而不是一下子降完。」

## 定位与改法（逐条）

### ① 目标路径上有岩浆 → 汇报一次
- `MaidWorkBehavior` 新增 `lavaOnPath(level, maid, target)`：从她眼睛到目标中心每 0.5 格采样，命中 `FluidTags.LAVA` 即真；`reportLavaPath` 播报一次（30 秒/条，`LAVA_PATH_REPORT_SINCE` 节流）。挖矿/伐木 tick 在目标锁定后各调一次。

### ② 目标跑出检索半径 → 单独清空一次
- `MaidWorkBehavior.targetOutOfRadius(maid, target, radius)`：**水平**距离 > radius（挖矿取 `MINE_SEARCH_RADIUS`、伐木取 `WOOD_SEARCH_RADIUS`）。挖矿/伐木在"目标失效检查"前各加一道：超出就 `targetPos = null` + 清进度 + `return`（**不进 `RECENT_DISCARD`/`blockedOres` 等任何记忆表**，下次扫描照旧可重选）。

### ③ 抗火 / 火焰保护饰品豁免（站在营火/岩浆块上不再惊慌）
- `SelfPreservationBehavior.envDamageCell`（"站在伤害方块上"那一项，营火/岩浆块属火伤）旧版**没有**抗火豁免（岩浆/着火那两项有）。补 `if (fireImmune(maid)) return false;`，三项口径统一。
- 说明：解读为"有抗火/火焰保护时**不应**为营火/岩浆块逃跑"（与另两项一致）；`MaidHeatEscape`（飞行侧烫伤脱困）本来就已豁免。

### ④ 载具避险：前瞻按速度加长 + 按障碍高度抬够
- `vehicleAvoidLift` 旧版"正前方 `VEH_PROBE_LEN`=8 格、一次抬 `VEH_AVOID_LIFT`=6 格、只探机身上方 3 层"——飞得快时撞上才反应、高墙探不到顶，看着像"完全不躲障碍"。改为：前瞻 `8 + 速度×10`（封顶 28 格）、竖直探到机身上方 `VEH_PROBE_UP`=5 层、抬升量 = `从障碍顶上过 + 1 格余量`（下限 6、上限 `VEH_AVOID_LIFT_MAX`=16）。**过了障碍前瞻归 0 → 回落到原高度**（与扫帚同款，四种飞行引擎共用）。

### ⑤ 伐木没斧提示：每次进入伐木模式顶多说一次
- `notifyNoAxe` 由"30 秒限频"改为"这次会话说过了没"（`NO_AXE_REPORT_SINCE.containsKey` 即返回）；`start()` 里 `NO_AXE_REPORT_SINCE.remove(maid.getId())` 清标记 → 进一次说一次。

### ⑥ 船与汤姆6 不吃电
- `outOfFuel` 在读数前先判引擎：`SHIP`（迷你快艇/速度船）与 `TOM6` 直接返回 false（船引擎无能量闸；汤姆6 推力段无条件）。

### ⑦ 接地收速：逐渐降为零
- 九百三十七 的 `killHorizontal(mount, 0.0)`（一下子抹零）改为 `aircraftGovernSpeedHard(mount, 0.0, AIRCRAFT_SPEED_DECEL)`（每拍最多降 0.04，0.80 → 0 约 20 拍 = 1 秒线性收完），接地段与完成段同口径。

## 触及文件

- 两树 × `com/maidsmart/task/MaidWorkBehavior.java`（①② 共享助手 + 节流表）
- 两树 × `…/task/MaidMineBehavior.java` / `MaidWoodBehavior.java`（①② 接入、⑤ 没斧提示）
- 两树 × `…/combat/SelfPreservationBehavior.java`（③）
- 两树 × `…/combat/MaidMountCompat.java`（④ 避险、⑥ 免电、⑦ 渐减速）
- `rounds/RELEASES.tsv`（第七十七次同号重打）+ `rounds/R938-work-and-flight-seven-roundup.md`（本文件）

## 验证

- **两树编译**：`compile_promaid.bat` / `compile_neo.bat` → `EXITCODE=0`（24 处锚点全部唯一命中，`FAILS=0 / TOTAL=24`）。
- **镜像自查**：新增/改动逐条读回两树——Forge 一律 SRG（`m_20185_/m_20186_/m_20189_`、`m_8055_`、`m_60819_().m_205070_(FluidTags.f_13131_/f_13132_)`、`m_20148_`、`m_19879_`、`m_9236_().m_46467_`），Neo 一律 Mojang（`getX/getY/getZ`、`getBlockState`、`getFluidState().is(FluidTags.WATER/LAVA)`、`getUUID`、`getId`、`level().getGameTime()`）。
- **全护栏**：11 个全 `FAIL 0`（`check_comment_style` **PASS** 931 文件 / 0 处；`check_dev_voice` / `check_i18n_keys` FAIL 0；`check_preset` **FAIL 0 / WARN 0**、DIY 基线仍 **172/176**——本轮不增删开关；`check_workflow` 3 条历史 WARN）。`check_arch` 一致差 0。
- **两个 jar**：forge **17587070** 字节（sha256 `CBBB6E99…82169D`）；neoforge **17623455** 字节（`027E6E54…1F6708`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 行更新 sha256 并列第七十七次同号重打；九百三十七 的旧字节 `6ECA0974…A6618F`（forge）/ `3C7FE7A9…A9D87B`（neo）作废。
- **未跑实机回归**（已部署，等开发者实测）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- ① **路径岩浆**：挖矿/伐木时若目标直线路径上有岩浆 → 气泡播报一次（含坐标），30 秒内不重复。
- ② **出圈清目标**：目标水平距离超出检索半径时被她丢掉、清进度、重新检索（不留记忆）；**判据**：日志可见 `mine 目标跑出检索半径→清空`。
- ③ **抗火豁免**：戴抗火效果/火焰保护饰品站在营火/岩浆块上，她**不再**惊慌逃跑。
- ④ **飞行避险**：开卓越前线载具（直升机/固定翼/飞艇）朝有明显建筑/山的方向飞 → 她应在撞上之前抬升越过，越过之后回落到原高度；日志可见 `载具避险`。
- ⑤ **没斧提示**：进入伐木模式后该提示**只出现一次**（换几次模式各说一次，同一次内不重复）。
- ⑥ **船/汤姆6**：把电量清零后迷你快艇与汤姆6 应仍能启动/起飞；直升机/固定翼/飞艇仍受电量闸约束。
- ⑦ **接地减速**：滑翔/固定翼接地后速度**逐渐**降到 0（不再"一下停死"），约 1 秒内停稳。
- 判定：逐条按上面现象观察即可。

## 边界（没动什么）

- **没动**：降落链路的条带选址/判据、`CORRIDOR` / `MAX_BEARING` / `PATTERN_ALT`、九百三十四~九百三十七 的降落改动；挖矿/伐木的 `approachOre` / `walkToBlockFace` / `unreachableBlocker` / `blockedOres` / `slideAnchor` / `findOre` 扫描逻辑；`MaidHeatEscape`（飞行侧烫伤脱困）本身；扫帚的避险（`MaidBroomDrive` 不动，只是载具档向它看齐）；`WorkStatusReporter` 九百三十七 的挖矿沉默口径；岩浆自保的其它分支。
- **口径变更（有意，非回归）**：① 新增路径岩浆播报；② 目标出圈即清（不入记忆表）；③ 营火/岩浆块纳入抗火豁免；④ 避险前瞻按速度加长、抬高上限 16；⑤ 没斧提示改每会话一次；⑥ 船/汤姆6 免电闸；⑦ 接地由"立即抹零"改"每拍 0.04 渐减"。
- **没新增配置开关**（新增常量均为 mod 内部：`VEH_PROBE_UP` / `VEH_AVOID_LIFT_MAX` / `LAVA_PATH_REPORT_SINCE`）；DIY 关闭条数基线仍 **172/176**；版本号仍 **1.3.2**。
- **已知取舍（如实记录）**：② 用"距她水平距离 > 检索半径"判据（按开发者原话）。检索框以**锚点**为心，`slideAnchor` 滑动时锚点可能离她一段距离，个别"其实还在框内、但离她 > 半径"的目标会被清掉——下一轮扫描会重挑最近的，代价是多一次重选（若实测发现她因此频繁换目标，可把判据改成对锚点而非对她）。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第七十七次同号重打。
- `DEBT.md`：不新增条目——七项均为"按开发者口径把现有行为收口 / 修正一处缺失的豁免"，不引入新的未验证功能面。
