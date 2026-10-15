# 实测九百三十九 抗火 / 火焰保护饰品豁免收口（"这里烫"语音 · 逃跑 · 险境脱离传送）

> 跑道：**开发者点名一件**（原话见下）。上一轮：`rounds/R938-work-and-flight-seven-roundup.md`。
> 本轮依据：开发者本次实测反馈（跑的是九百三十八 的 jar）+ 代码逐处定位。

## 排班

- 只做"豁免收口"这一件事；功能面（数值/矿表/建筑/对话/战斗）一个不动；版本号仍 **1.3.2**。
- 步骤：改 Forge → 镜像 Neo（同模板 + 名字映射，逐锚点断言唯一，命中数 ≠ 1 即中止）→ 两树 `javac EXITCODE=0` → 全护栏 `FAIL 0` → 打 jar → 部署 4 实例 `match=True`。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

开发者原话：「这里烫......这个语音和它的触发似乎没有抗火和火焰保护的豁免。有这两个东西之后还是会触发传送和逃跑。」

## 定位（为什么九百三十八 的豁免没兜住）

九百三十八 ③ 只给 `envDamageCell`（"站在伤害方块上"，营火/岩浆块那一档）补了一道 `fireImmune` 闸，但"抗火 / 火焰保护"这套判据在 `SelfPreservationBehavior` 里本来就有**三套写法各自为政**：

1. `hasFireResist(maid)`——只看 `minecraft:fire_resistance`，且**剩余 ≥30 秒（600 tick）才算命中**。这道门槛的本意是"提前续杯"（泡岩浆时效果 30 秒内要过期，当作没有好让她早点补一瓶），却被当成"疼不疼"用了。
2. 手写 `COMBAT_FIRE_PROTECT_BAUBLE && hasFireProtectBauble(maid)`——TLM 火焰保护饰品。
3. 两者相加的两行——散在 `envDangerCritical` / `envDangerSoft` / `giveUpMovement`×2 / 岩浆链路入口 / 着火链路入口。

于是有两个洞：

- **`fireImmune` 卡在 ≥30 秒门槛上**（它 = `hasFireResist` OR 饰品）。抗火药水只剩最后 30 秒时，站在营火 / 岩浆块上的她就被判成"会疼" → `envDamageCell` 为真 → 喊「这里烫！我走开点！」并走开 / 瞬移。这正是开发者看到的"语音 + 逃跑 + 传送"。
- **两处漏了火焰保护饰品**：`environmentalEscape` 第 5 档"附近有岩浆，我绕开走"（只判 `!hasFireResist`）与 `avoidLavaMovement`（会话外避让，只判 `hasFireResist`）——只有饰品、没喝药时照旧绕路 / 逃跑。
- 另外 **`DangerEscapeHandler`（险境脱离）此前对热源一格豁免都没有**：它每 0.5 秒全图巡检，把站在危险方块上的她直接 `m_6034_` 挪到最近安全格（每女仆 1.5 秒冷却）——岩浆块/营火在地狱遍地都是，戴抗火照样被反复弹开，这就是"传送"。

## 改法

### ① 唯一判据 `fireProof`（"要不要为火逃跑 / 灭火 / 绕开岩浆"）
- `SelfPreservationBehavior` 新增 `private static boolean fireProof(EntityMaid)` = `hasFireResist(maid)`（≥30 秒）`||`（`COMBAT_FIRE_PROTECT_BAUBLE` 且 `hasFireProtectBauble`）。
- 替换八处两份写法：`envDangerCritical`、`envDangerSoft`、`giveUpMovement`（岩浆腿 / 着火腿）、岩浆链路入口（`if (hasFireResist) return` 那一句）、着火链路入口（`hasFireResist` + 紧邻的饰品块合成一条）、第 5 档"附近有岩浆我绕开走"、`avoidLavaMovement`。
- 语义**保持不变**（仍是 ≥30 秒门槛），只是补上漏掉的饰品、去掉重复写法。

### ② "烫不疼"改用 `hasFireResistNow`（只看有没有效果，不看余量）
- 新增 `private static boolean hasFireResistNow(EntityMaid)` = `hasFireResistAtLeast(maid, 1)`；原 `hasFireResist` 改为 `hasFireResistAtLeast(maid, 600)`，效果判定循环收进 `hasFireResistAtLeast(maid, minTicks)`（饮用 / 喷溅 / 滞留通用）。
- `fireImmune(maid)`（对外：供 `MaidHeatEscape` 飞行烫伤脱困 与 `envDamageCell` 站伤害方块用）改用 `hasFireResistNow` —— "免不免疼"只看效果在不在，"续杯"才看余量。抗火药水最后 30 秒同样免疼。
- `fireImmune` 仍是 `public static`，新增调用方见 ③。

### ③ `DangerEscapeHandler` 热源纯危险豁免
- `DangerBlocks` 新增 `HEAT` 子集（火 / 灵魂火 / 岩浆 / 营火 / 灵魂营火 / 岩浆块）与 `heatOnlyDanger(level, x, y, z)`：口径与 `cellDangerous` 逐格对齐（本体 / 脚下在表内且**都**是热危险；头顶灼烧型也只算热），只要有一个非热贡献（仙人掌 / 细雪 / 石笋等）就返回 false。顺手把 `cellDangerous` 里那段内联头顶判定抽成 `isBurnAbove`，两处共用。
- `DangerEscapeHandler.onServerTick`：`inDanger` 之后加一道——`SelfPreservationBehavior.fireImmune(maid)` 且 `DangerBlocks.heatOnlyDanger(level, feet)` → `LAST_TRY.remove` 不记账 + `continue`（不搬走；豁免一消失，下一轮立即恢复抢救）。

## 触及文件

- 两树 × `com/maidsmart/combat/SelfPreservationBehavior.java`（①②，八处替换 + 三个助手）
- 两树 × `com/maidsmart/protect/DangerEscapeHandler.java`（③）
- 两树 × `com/maidsmart/tool/DangerBlocks.java`（③ `HEAT` / `heatOnlyDanger` / `isBurnAbove`）
- `rounds/RELEASES.tsv`（第七十八次同号重打）+ `rounds/R939-heat-immunity-exemption-consolidation.md`（本文件）

## 验证

- **两树编译**：`compile_promaid.bat` / `compile_neo.bat` → `EXITCODE=0`（24 处锚点全部唯一命中，`FAILS=0 / TOTAL=24`）。
- **镜像自查**：新改动逐条读回两树——Forge 一律 SRG（`m_20077_` / `m_6060_` / `m_20148_` / `maid.m_9236_()`），Neo 一律 Mojang（`isInLava()` / `isOnFire()` / `getUUID()` / `maid.level()`）；`DangerBlocks` 的 `HEAT` / `heatOnlyDanger` / `isBurnAbove` 两树逐字一致（不含混淆名）。
- **全护栏**：11 个全 `FAIL 0`（`check_comment_style` 首轮 **FAIL**——新写的 `fireImmune` javadoc 9 行 > MEMBER_MAX 8，收成 8 行后 **PASS** 931 文件 / 0 处；`check_dev_voice` / `check_i18n_keys` / `check_panel_coverage` FAIL 0；`check_preset` **FAIL 0 / WARN 0**、DIY 基线仍 **172/176**——本轮不增删开关；`check_workflow` 3 条历史 WARN）。`check_tree_mirror` 只读报告，非阻塞。
- **两个 jar**：forge **17587429** 字节（sha256 `344705EC…3FDE05`）；neoforge **17623809** 字节（`B42CD6C2…E0DC9F`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**（其中 Forge 47.4.10 与龙之冒险两处走了提权拷贝）。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 行更新 sha256 并列第七十八次同号重打；九百三十八 的旧字节 `CBBB6E99…82169D`（forge）/ `027E6E54…1F6708`（neo）作废。
- **未跑实机回归**（已部署，等开发者实测）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- ① **语音 / 逃跑**：戴抗火效果（含只剩几十秒）或火焰保护饰品，站在营火 / 岩浆块上 → **不再**出现「这里烫！我走开点！」，也不再走开或瞬移。
- ② **险境脱离传送**：同上配置站在岩浆块 / 营火 / 火里 → **不再**被"险境脱离"每 1.5 秒弹到旁边（日志不再刷 `险境脱离` 那一条）。
- ③ **非热危险不受影响**：站在仙人掌 / 甜浆果丛 / 细雪上，即便戴抗火 → 仍照常脱困（这条豁免只认热源）。
- ④ **续杯不受影响**：泡岩浆且抗火只剩 <30 秒 → 仍会**提前补一瓶**（`fireProof` 的门槛没动，"免疼"与"续杯"分开）。
- 判定：逐条按上面现象观察即可；日志搜 `险境脱离` / `烫伤脱困`。

## 边界（没动什么）

- **没动**：降落链路（`MaidMountCompat` 一个字节没碰）；挖矿 / 伐木的扫描与接近链；`MaidHeatEscape` 传送落点与冷却；`MaidDangerPathMixin` 寻路避让（只管"别走进去"，不属逃跑/传送）；`DangerEscapeHandler` 的非热危险分支、坐姿 / 骑乘 / 自保让位、1.5 秒冷却；飞行侧"预测式避让"（`MaidFlightHazardGuard`）。
- **口径变更（有意，非回归）**：① "为火逃跑 / 灭火 / 绕开岩浆"的豁免统一走 `fireProof`，并补上此前漏掉的火焰保护饰品；② `fireImmune` 不再卡 ≥30 秒——**免疼**与**续杯**正式分成两条判据；③ 险境脱离对"纯热危险格子 + `fireImmune`"不再搬走。
- **没新增配置开关**（新增常量均为 mod 内部：`HEAT` 集合、`heatOnlyDanger` / `isBurnAbove` / `fireProof` / `hasFireResistNow` / `hasFireResistAtLeast`）；DIY 关闭条数基线仍 **172/176**；版本号仍 **1.3.2**。
- **已知取舍（如实记录）**：③ 判"纯热危险"只在**她站立的那一列**（本体 / 脚下 / 头顶灼烧型）上分类，与 `cellDangerous` 同口径——若她同时踩着岩浆块（热）又被旁边的仙人掌（非热）判定，则按"有非热贡献"处理、照常脱困（宁可多救一次）。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第七十八次同号重打。
- `DEBT.md`：不新增条目——本轮是把散落的豁免判据收口成一条并补上一处缺失（险境脱离的热源豁免），不引入新的未验证功能面。
