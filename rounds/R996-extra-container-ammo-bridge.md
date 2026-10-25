# R996 精妙背包联动：弓/枪弹药从未桥接（三条桥接 + 实证答复）

> 上一轮：`rounds/R995-pink-fire-cutout-render-layer.md`（粉火 cutout 渲染层未登记，1.3.3 同号重打，**未发布**）。
> 开发者原话：「女仆和精妙背包联动在哪了，为什么我试了好多次没试出来，女仆装了背包之后也不会从背包
> 里面取东西和存东西，而且给她一把弓，弓箭在精妙背包也不会用，枪械和子弹也同理」

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug（弓/枪弹药与额外容器未接线，要改对）+ B 答复（"联动在哪 / 为什么试不出来"，
  用 javap + 调用点枚举当着字节码说）。
- **开工前读过**：
  - `rounds/RELEASES.tsv`：1.3.3 两条 `workspace`（R995 重打，未发布）→ 本轮按开发者既有口径**同号重打**
    （**第一百三十二次同号重打**，前一次字节作废）。
  - `ARCHITECTURE-SWITCHES.md`：额外容器有总开关 `misc.backpackOverflow`（默认开，**只闸"装进去"那一侧**；
    取物侧跟 TLM 本体口径走、本就无开关——见 `MaidExtraContainer` 类注）→ 本轮**不新增开关**（修错，
    且新路径沿用同一开关与同一额外容器体系）。
  - `DEBT.md`：BD-29 / BD-30 均 `已清`；本条为新发现（见「台账」）。
  - 上一轮记录 R995：1.3.3 未发布、本轮顺延 R996。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位（联动全链路 + 三处未接线点） | 每条结论指着 javap 字节码或 file:line 调用点（见「评估」） | ☑ |
| 1 | 两树改 + 编译 | 箭矢三处 + 枪械弹药桥（判定 contains / 取物 pull 两处）+ 两个取物调用点；两树 `javac` `EXITCODE=0` | ☑ |
| 2 | 14 护栏 | `FAIL 0 / WARN 0` | ☑ |
| 3 | 同号重打 1.3.3 + 装 4 实例 | 双 jar；`match=True`；`RELEASES.tsv` 两条新行（void 旧字节） | ☑（双 jar + 台账当轮完成；装机当轮被运行中的游戏挡住（未绕过），由 R997 一并部署，见「验证」补记） |
| 4 | 交开发者验收 | 实机复看：饰品栏放精妙背包后 ①弓/弩弹药 ②枪械弹药 都能用；日志搜「额外容器」 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。**先把前提摆上**（见「评估」的清单：Curios 在场 + 女仆饰品
  开着（默认开）+ 精妙背包**装在女仆的饰品栏**（不是背包、不是手上））：
  1. **弓**：精妙背包里放箭 → 远程空袭（或扫帚模式）带弓/弩 → 她应当取箭开火（日志
     `[额外容器] …从精妙背包/旅行者背包取来…minecraft:arrow`）；贴脸自保放箭同理。
  2. **枪**：精妙背包里放对口径的子弹（或弹药箱）→ 给她一把枪 → ① 会自动切进枪械战斗
     （以前弹药只在背包里就**永远不进池**）② 打空弹匣后应当自己从精妙背包补弹继续打
     （日志同上，「取来 tacz:ammo」）。
  3. 反例（不算 bug）：背包里只有**对不上口径**的弹 → 判定为缺弹药（与枪械 mod 自己的
     `canReload` 同口径，见「边界」）。
  4. 存东西方向（她自己的背包满了 → 收进精妙背包）是既有功能（v1.2.4 起），本轮未动；
     验收时若发现"取/存不生效"，先核对上面三条前提与 `misc.backpackOverflow` 开关。

## 需求 / 来源

- 开发者实机反馈一条（原文见上）。

## 评估

### 联动在哪（全链路，javap 实证）

| 层 | 位置 | 证据 |
|---|---|---|
| TLM 本体 | `com.github.tartaricacid.touhoulittlemaid.compat.extracontainer.*`（两版 jar 类集一致） | 1.20.1 / 1.21.1 两个 TLM jar 的 entry 表：`MaidContainerCache` / `ContainerRef` / `MaidInventoryRef` / `BackpackProvider` / `ExtraContainerManager` / `curios/CuriosSlotRef` / `curios/ExtraContainerRequestHandler` / `curios/ExtraContainerPickupHandler` / `curios/ExtraContainerEquipHandler` / `sbackpack/curios/SBackpackSlotRef` / `tbackpack/curios/TBackpackSlotRef` |
| 精妙背包接入 | `SBackpackSlotRef.getBackpackStack`：取 curios 槽里那件 `SBackpackCompat.isBackpack(stack)` 的背包栈；`extract/insert/containing` 全走 SB 自己的能力 `CapabilityBackpackWrapper.getCapabilityInstance()` → `IBackpackWrapper.getInventoryForUpgradeProcessing()`（**不是** Forge `ITEM_HANDLER`——所以必须走 TLM 这层，不能自己找） | `javap -c -p`（TLM jar，本轮实证：`SBackpackSlotRef.getBackpackStack/extract/insert/containing` 全量字节码） |
| 取物 | `ExtraContainerRequestHandler.onMaidRequestItem`：`getContainers(maid)`（下标 0 = `MaidInventoryRef` 她自己，`size()<=1` 直接 return）→ 从下标 1 起逐个 `extract(maid, filter, maxCount)` → `transferToMaidInv`（`maid.getAvailableInv(false)` + `ItemHandlerStacked` 真插入；放不下时 `transferToBackpack` 把她背包里的同类**整格挪进容器腾地方**再试；仍放不下把余量塞回容器）→ `event.setRequestedItem(已放入的部分)` + `setCanceled(true)` | `javap -c -p`（TLM jar，本轮实证：`ExtraContainerRequestHandler.onMaidRequestItem` 全量字节码） |
| 触发口 | `ItemsUtil.findStackSlot(inv, filter, maxCount)`（3 参版）：先扫她自己背包，未命中且 handler 是 TLM 的 `MaidInvWrapper` → new + post `MaidRequestItemEvent` → 再扫一遍（2 参版直接委托 3 参版） | `javap -c -p`（TLM jar，本轮实证：三参版方法体里 new/post `MaidRequestItemEvent`）；`TaskBowAttack.findArrow` 就调它（见下表） |
| 装备时刷缓存 | `ExtraContainerEquipHandler.onCurioChange` → `MaidContainerCache.onEquipped/onUnequipped`（**放进饰品栏即刻生效，不用重进世界**） | `javap -c -p`（TLM jar，本轮实证：`ExtraContainerEquipHandler.onCurioChange` 全量字节码） |
| 本模组 | `com.maidsmart.tool.MaidExtraContainer`：`hasAny` / `canAccept`（模拟 insert）/ `contains`（取出 1 个立刻放回，净零）/ `pull`（`maid.getAvailableInv(false)` + `ItemsUtil.findStackSlot`，真搬）/ `overflow`（真插入）；总开关 `misc.backpackOverflow`（只闸 overflow/canAccept） | `promaid_src/com/maidsmart/tool/MaidExtraContainer.java`（类注即全链路说明） |
| 已接线位点 | 取物 46 处 + 只读探针 24 处 + 溢出装填 6 处（搭方块、镐/斧/锄、种子骨粉、烹饪/酿造用料、食物/药水/蜂蜜、金苹果、激流三叉戟、烟花、扫帚、火把、蓝图材料、超越维度入库、发还物品…） | 全树枚举（本轮实证：`MaidExtraContainer.` 108 命中 = 46 pull + 24 contains + 6 overflow + 类注/转发） |

### 三条前提（一件不满足 = 她"完全不认背包"，这就是"试不出来"的第一大来源）

1. **Curios 在场**（`extracontainer.curios` 包只在 Curios 加载时注册，`SBackpackSlotRef` 是 curios 槽实现）；
2. **TLM「女仆饰品」开着**：`MaidConfig.ENABLE_MAID_CURIOS`——本机两个实例配置实测 `EnableMaidCurios = true`
   （1.20.1 `1.20.1-Forge_47.4.10/config/touhou_little_maid-common.toml:15`；1.21.1 同名配置 `:15`）；
3. **精妙背包装在女仆的饰品栏里**（curios `back` 槽）——**拿在手上 / 放进她的"背包"都不算**
   （`SLOT_PRIORITY` 里精妙背包坐 curios 的 `back` 槽；`getContainers` 只认饰品）。放进饰品栏即生效
   （`ExtraContainerEquipHandler` 刷缓存），不需要重进世界。

**环境第二坑（很可能就是"试了好多次"的主因）**：本机四个实例里**只有 1.21.1-NeoForge
（`sophisticatedbackpacks-1.21.1-3.26.3`）与 龙之冒险 v2.4a（`sophisticatedbackpacks-1.20.1-3.22.5`）
装了精妙背包**；`1.20.1-Forge_47.4.10` 只有 Curios、**没有精妙背包**，专服 pack1201 也没有——
在没装 SB 的实例里怎么试都不可能生效（TLM 的 `SBackpackCompat` 恒 false → 容器列表永远只有"她自己"）。

### 为什么弓/枪试不出来（三处**我们自己的**路径没接线；TLM 自己那条是通的）

- **TLM 自己的弓/弩任务：本来就通**——`TaskBowAttack.findArrow`（两版字节码一致）=
  `maid.getAvailableInv(true)` + `ItemsUtil.findStackSlot(inv, bow.m_6437_())` → 她自己背包没有就发
  `MaidRequestItemEvent` → TLM 从精妙背包搬箭。**TLM 的 gun_attack 任务：完全不管弹药**——
  `TaskGunAttack` javap 里 `findStackSlot / getAvailableInv / MaidRequestItemEvent` **0 命中**，
  弹药全交枪械 mod（见下）。
- **弓（我们的三条路径）**：
  1. `MaidFlightKit#hasArrowFor`（飞行/扫帚激活判据）只扫副手 + 她自己背包 → 箭在精妙背包里
     判"缺弹药"、**连模式都不激活**；
  2. `MaidFlightRangedTask#findArrow`（飞行/扫帚开火取箭）只扫她自己背包 → 一发不出；
  3. `SelfPreservationBehavior#takeArrow`（贴脸自保放箭）同样只扫她自己背包。
- **枪（为什么枪械 mod 自己看不见）**：TACZ `AbstractGunItem.hasInventoryAmmo` /
  `canReload` / `findAndExtractInventoryAmmo` 扫的是**女仆实体自己的** ITEM_HANDLER 能力
  （javap：`entity.getCapability(ForgeCapabilities.ITEM_HANDLER)`；1.21.1 版
  `getCapability(Capabilities.ItemHandler.ENTITY)` 同构），而该能力的内容 = **护甲 + 双手 +
  背包(maidInv) + 饰品**（1.20.1 `EntityMaid.lambda$getCapability$9` = `CombinedInvWrapper(armor,
  hands, maidInv, maidBauble)`；1.21.1 `EntityMaid.getAllInv()` = 同四件——**她"自己的"背包在能力里**，
  这正是"弹药放进**她的背包**枪就能用"的原因）——**精妙背包里的东西是背包物品自己的嵌套容器，
  不在这个能力里**，对枪械 mod 等于不存在。于是我们这边：
  4. `GunCompat#hasGunAndAmmo`（枪械任务进池闸，`AutoCombatPools:190`）=
     `canFeed`（枪械 mod 自己答）→ 弹只在她精妙背包里时**永远不进池**、不会切枪械模式；
  5. `MaidFlightKit#hasAmmoForWeapon`（飞行/扫帚模式门禁，314 行）= `canFeed || canReload` → 同病，
     判"缺弹药"不起飞；
  6. 就算进去了，`GunCommonUtil.performGunAttack` 的换弹也只会去扫上面那个能力面 → **打不响**。
- **"存"（她→精妙背包）**：只在"她自己背包塞不下"时触发（`overflow`），接线在拾取/农场/工作/
  超越维度/发还五处；这是 v1.2.4 起就有的功能，本轮未动。

### 三处修复（两树镜像，`v1.3.3 实测一千〇三`）

1. **箭矢桥**（判据 + 取物两处同口径，与既有 `hasRiptide`/`fetchRiptide` 完全同构）：
   `hasArrowFor` 末尾加只读探针 `MaidExtraContainer.contains(判据)`；`findArrow` / `takeArrow`
   在自己背包扫空后 `MaidExtraContainer.pull(判据, 1)` 再扫一遍。
2. **枪械弹药桥**（`GunCompat` 新增，唯一实现）：
   - `ammoMatchesGun(gun, s)`：TACZ 走**它自己的** `IAmmo.isAmmoOfGun(gun, ammo)`（口径对得上，
     javap 实证两版签名一致）+ 弹药箱 `IAmmoBox.isAmmoBoxOfGun`（既有 `boxHasAmmoFor`）；
     卓越前线走既有 5 类弹药判据；反射不可用回退旧宽松口径（与 `looseAmmoScan` 同源）；
   - `ammoInExtraContainer(maid, gun)`：只读探针（`contains`，净零）；
   - `fetchAmmoFromExtra(maid, gun)`：`pull` 搬**一组**进她自己的背包（20 tick/只限频）；
   - `fetchAmmoIfNeeded(maid, gun)`：`canFeed || canReload` 为真 → 一个字不动；否则先只读探针、
     真取物再 pull（判据与取物同口径）。
3. **门禁 + 取物两个调用点**：
   - `hasGunAndAmmo` = `canFeed || canReload || ammoInExtraContainer`（与 `hasAmmoForWeapon`
     的或门口径对齐——"打得响 或 换得上 或 额外容器里有"）；
   - `MaidFlightKit#hasAmmoForWeapon` 枪械分支 += `ammoInExtraContainer`；
   - 取物调用点（"她已经决定要用这把枪"）：**飞行/扫帚** `MaidFlightCombatBehavior#tickGunState`
     （每 tick、所有门之前、幂等，且 `MaidBroomBehavior` 也走 `fireRanged` → 同一条）；
     **地面枪械任务** `MaidToolAutoEquip#ensureForTask` 的 `gun_attack` 分支（core 行为
     `MaidToolAutoEquipBehavior` 任何 activity 每 tick 都跑）。

## 改法

- 见「评估」的"三处修复"。全部沿用既有额外容器体系（`MaidExtraContainer`）与既有总开关
  `misc.backpackOverflow`，**不新增开关、不新增配置项**；取物侧与 TLM 本体口径一致（本就无开关）。
- **被否方案**：
  （a）自己反射精妙背包（`CapabilityBackpackWrapper`）——TLM 已把这层做完且覆盖旅行者背包与
  将来任何注册进它体系的容器，自己找要硬编码 SB 版本与内部方法名（类注里 v1.x 的老结论）；
  （b）把弹药"判定有"却**不搬**（只在门禁里加 contains）——会出现"判定齐备、她却不打"的
  分裂（本模组反复吃过亏的那类），所以取物调用点必须跟判定同口径地真搬；
  （c）改枪械 mod 的能力面（把精妙背包挂进女仆实体的 ITEM_HANDLER 能力）——那是 TLM/枪械 mod
  的地盘，且"嵌套容器逐格展开"语义上也不对（箱子/背包的内容不该伪装成她的装备槽）。

## 触及文件

- Forge 树 6 个：`combat/GunCompat.java`、`combat/MaidFlightKit.java`、`combat/MaidFlightRangedTask.java`、
  `combat/SelfPreservationBehavior.java`、`combat/MaidFlightCombatBehavior.java`、`task/MaidToolAutoEquip.java`。
- Neo 树镜像同 6 个。
- 新配置项 0；新 i18n key 0；新类 0；新 mixin 0。

## 验证

- 命令 + 结果：
  - 两树 `javac` **EXITCODE=0**（`gen_compile.py`+`compile_promaid.bat` / `gen_compile_neo.py`+`compile_neo.bat`）。
  - 14 护栏**全 FAIL 0 / WARN 0**（改后一轮 + `RELEASES.tsv`/`DEBT.md` 登记后复跑一轮，均全绿；
    `check_comment_style` PASS——初稿 4 处 LONG-BLOCK 已按限长压缩重写；`check_reflection` 按守卫自带
    指引**显式上调基线并留痕**：TACZ 弹药口径比对新增 `Class.forName`/`getMethod` 各 1 处/树，
    基线注释写明缘由）。
  - 双 jar（**同号重打**）：`patched/promaid-1.3.3-forge-1.20.1.jar` **17917818** 字节，sha256
    `910b4cd5…9d5d9e`；`patched/promaid-1.3.3-neoforge-1.21.1.jar` **17965396** 字节，sha256
    `0ebf80bd…6cf86e`；构建内混入审计 + 10 护栏 PASS，桌面快照自动同步
    （forge=910b4cd55327d927 / neo=0ebf80bd71b5b37b）。
  - **部署 4 实例：被本机运行中的游戏挡住（未绕过）**——`python tools\deploy\deploy_all.py` 返回
    `DEPLOY REFUSED: 1 个 java/javaw 进程在运行（游戏或服务器）——先全部退出。`（java.exe
    PID 17500，约 6.4 GB，控制台会话）。这是部署脚本的硬门禁（运行中的 JVM 锁着 mods 目录），
    按红线**不杀进程、不绕过守卫**；待开发者关掉游戏后重跑同一条命令即可（预期 `match=True` ×4、
    旧字节自动备份进 `patched/backup_old/`）。
  - 专服回归三件：**待部署后跑**（`test_server.py 1201` / `test_server.py neoforge1211` /
    `test_maid_load.py neoforge1211`）——回归依赖实例里是新字节，先部署再回归。
- **反证**（修 bug 必填）：
  - "取不出箭"不是精妙背包那一侧的问题：TLM `SBackpackSlotRef` 读的是 SB 自己的能力
    `CapabilityBackpackWrapper.getCapabilityInstance()`（javap 全量），且 **TLM 自己的
    `TaskBowAttack.findArrow` 走 `ItemsUtil.findStackSlot` 本来就是通的**——若 SB/桥本身坏了，
    TLM 本体那条也该坏；坏的只是**我们自己的三条取箭路径没接这座桥**。
  - "枪不认弹"不是 TACZ 不查库存：`AbstractGunItem.hasInventoryAmmo/canReload` javap 明确查
    实体 ITEM_HANDLER 能力，而女仆该能力 = 护甲+双手+背包+饰品（两版 javap 实证）；精妙背包是
    被嵌套的**物品自己的**容器、不在能力面里。旁证：弹药放进**她的背包**（maidInv）枪就能用
    （既有实测），差别只在弹药藏在哪一层。
  - 修复后实机若仍不生效，先按「三条前提 + 环境」核对（该实例装了精妙背包吗？背包在**饰品栏**
    且 Curios 在场吗？`EnableMaidCurios` 开着吗？）再看日志有无「额外容器」行——本机
    `1.20.1-Forge_47.4.10` 与专服 pack1201 **没装 SB**，在那些实例里贴不出成功现象是预期内的。

- **补记（实测一千〇四 / R997 轮）**：R996 字节（`910b4cd5…9d5d9e` / `0ebf80bd…6cf86e`）已被
  **第一百三十三次同号重打**取代、不再单独部署；4 实例装机与专服回归三件由 R997 一并完成
  （`match=True` ×4、三件 PASS，见 `rounds/R997-schedule-teleport-direct-and-diamond-sword-forensics.md`）。
  `RELEASES.tsv` 里 R996 两条行尾的"部署 match=True / 回归 PASS"已按当轮实况更正为
  "被运行中的游戏挡住（未绕过）"。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）

- 状态：`未验收`（等开发者按「排班」两条场景实机复看）。
- 未验收之前：不写 changelog、不写 release 说明、不发平台。

## 边界（没动什么）

- TLM 本体 / 精妙背包 / Curios 的类：一字未动（只调 TLM 的公开 API）。
- `MaidExtraContainer` 本身：一字未动（只作为调用方接入新位点）。
- 存东西方向（overflow）与既有 46 处取物位点：未动。
- TLM 自己的弓/弩任务（本来就会取精妙背包的箭）：未动。
- 口径边界：**对不上口径**的弹药不算"有弹药"（TACZ 走 `isAmmoOfGun`、弹药箱走
  `isAmmoBoxOfGun`；反射不可用才回退宽松档）——与枪械 mod 自己的换弹判定同源，宁可判缺
  也不放"打不响的齐备"。
- 版本号仍 **1.3.3**（未发布时段同号重打，旧字节在 `RELEASES.tsv` 记废）。

## 台账

- 清掉：无既有债号。本条属**新发现**，登记 `DEBT.md` BD-31（弓/枪弹药不读额外容器，战斗组），
  状态 `已清@实测一千〇三`；§0 模块索引「战斗 / 骑乘 / 载具 / 蹲下语义」行补 BD-31。
- 发布登记：`rounds/RELEASES.tsv` 追加 **1.3.3** 两条 `workspace`（**第一百三十二次同号重打**：
  本行现字节；前一次 `0cb6ebb3…eaf0e8` / `9f0ac999…042c02` 作废）。
- 升号：无（1.3.3 未发布 → 同号重打，§5.5 清单不动）。
- 新增债：见上（BD-31 本轮即清）。
