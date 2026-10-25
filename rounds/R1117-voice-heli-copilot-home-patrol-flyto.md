# R1117 无料播报 / 删「不是燃料」 / 直升机副驾爬升 / home 悬停与航迹 / 飞过去不起飞

## 排班

- **跑道**：C 功能修复（承接 R1116 验收反馈；全部是既有功能的边界修正，无新开关）。
- **开工前读过**：R1116 轮次文档与部署结果、`MaidCookBehavior`（`findStation`/`processFurnace`/
  `notifyNoIngredient`/`pickFurnaceKind`）、`MaidMountCompat.hasPlayerAboard/getNthEntity/
  firstFreeSeatExcept`、`RideBindManager` 的 home 闸与无敌人分支、`MaidAirCombat.holdHere`、
  `MaidFlightFollowBehavior.m_6114_`（canUse）/`aimOf`、`MaidTravelTarget.aim`、
  `PatrolFlight.effective/nextPoint`、玩家 NeoForge 实例 `logs/promaid.log` 与 `latest.log`、
  SWB `VehicleEntity` 的 javap（`addPassenger`/`getNthEntity`/`getFirstPassenger`）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 复现「无料语音不触发」 | 找到"一开始没料 / 烧完最后一份"两条都不经过旧播报分支 | ☑ |
| 1 | 无料播报接到解绑与扫台两处 | 30 秒限频；有别的炉型能烧时静默 | ☑ |
| 2 | 删除「（不是燃料）」并重训语音 | 气泡、英文 i18n、manifest、日语台词、ogg 全部同步 | ☑ |
| 3 | 直升机副驾一直上升 | `hasPlayerAboard` 能看见 SWB 的 orderedPassengers | ☑ |
| 4 | home+直升机：无航迹悬停、有航迹沿轨 | 无航迹走 home 悬停；航迹生效时算不出点也悬停、绝不跟随主人 | ☑ |
| 5 | 「飞过去」能点但不起飞 | 显式命令不受飞行跟随总开关/接敌/任务占用/威胁/地面到点清目标限制 | ☑ |
| 6 | 两树编译/护栏/双 jar/部署/四测试/轮次文档 | 全绿 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者，见文末「验收」。

## 需求 / 来源

> 「1.没有原料的语音无法触发，（不是燃料）这个删掉，太人机了
> 2.现在女仆坐直升机情况更糟了，首先是女仆开直升机玩家坐副驾时飞机会一直往上飞；然后是女仆处于
> 乘坐直升机+home模式时不会按绑定的轨道飞行，而是跟随主人。（之前的版本是悬停。我要的是，
> home模式+直升机，如果未绑定轨道，女仆会正常悬停，绑定完成，女仆会按轨迹飞行。总体逻辑类似于
> 巡逻航图下的扫帚女仆
> 3.启动飞过去，但女仆压根不飞」

## 评估

### ① 无料语音为什么触发不了

- 旧播报只挂在 `processFurnace` 的 `nofeed` 分支（"已绑炉子、槽 0 空且喂不进料"）。
- **一开始就没有原料**：`findStation` 先问 `pickFurnaceKind`，返回 NONE 就直接
  `return null`——连炉子都不会绑，`processFurnace` 根本不会跑。
- **烧完最后一份原料**：R1116 的 `furnaceMatchesInv` 先判不匹配 → 解绑返回，
  也走不到 `nofeed` 分支。
- 所以两条玩家最常遇到的"没料了"都不播报；玩家听到的那句只可能来自"绑上之后才发现喂不进"
  的罕见空转。
- 文本里的"（不是燃料）"是写给开发者的解释，玩家读起来像机器人在澄清术语。

### ② 直升机副驾一直上升：SWB 的乘客列表不是原版那张

- `hasPlayerAboard` 遍历的是 `mount.getPassengers()`。
- javap 反编译 SWB `VehicleEntity.addPassenger` 实证：它**不调用**
  `super.addPassenger`，只写自己的 `orderedPassengers`；原版 `Entity.passengers`
  始终为空（`getFirstPassenger` 被覆写，所以驾驶位判断一直是对的）。
- 于是"主人坐副驾"这一档恒为 false，驱动落到"跟随主人"：`hoverAt(owner.x, owner.z, owner.y)`，
  而 owner 就在同一台机器上，`owner.y` 随载具一起涨 → 目标 = 自己高度 + 3 → **永久爬升**。
  玩家日志实证：`跟随档(比主人高3格) 距目标=0格 高差=3 竖直速度=+0.19`，高差 3 一直不收敛。

### ③ home+直升机不巡逻

- 生效判据 / home 闸 / 航迹点都在 R1116 放宽过，但无敌人分支的兜底仍是"跟随主人"：
  一旦 `nextPoint` 这一拍返回 null（路径刚重建、偏得太远），home 女仆会掉进 follow 档。
- 玩家要的口径与扫帚一致：home + 没航迹 = 悬停；home + 航迹 = 沿轨；**任何时候都不该追主人**。

### ④ 「飞过去」不起飞

四个独立原因，任何一个都足以让她站着不动：

1. `MaidFlightFollowBehavior.canUse` 第一句是 `if (!cfg()) return false;`——
   **飞行跟随总开关关着**时，玩家点「飞过去」也不会执行（按钮只看资格，不看这个开关）。
2. `MaidTravelTarget.aim` 用 4 格判到达。目标点是玩家按中键时的**脚底**坐标，玩家站在
   目标旁边点「飞过去」时，女仆往往已经在 4 格内 → 目标**下单即被清掉**。
3. 自动追主人用的软闸（接敌不可打断、任务占用、附近有敌对生物）对显式命令也生效。
4. 空袭女仆在牵绳档（未起飞）时本来就最容易命中上述几条。

## 改动

### ① 播报（两树 `MaidCookBehavior`）

- 新增 `notifyNoIngredient(level, maid)`：
  - 先问 `pickFurnaceKind`：还有别的炉型能烧 → **静默**（换炉中）；
  - 30 秒最多一次（新字段 `noIngredientVoiceAt`）；
  - 文本：`没有能烧的原料了…主人，给我一点生食或矿石吧？`
- 接到三处：`findStation` 返回 null（一开始就没料）、`furnaceMatchesInv`/营火不匹配解绑、
  `nofeed` 分支（原来只在那里播）。
- 英文 i18n、`voice/manifest.json`、`tools/voice/gen_voice_pack.py` 的日语台词同步去掉
  "（不是燃料）"；`cook_no_food.ogg` 重新合成、归一化（-1.5 dB、libvorbis q4）并导入两树
  （65,272 字节）。

### ② 副驾（两树 `MaidMountCompat.hasPlayerAboard`）

- 原版 `getPassengers()` 之后追加 SWB 通道：`isVehicle(mount) && mGetNthEntity != null` 时
  逐座 `getNthEntity(i)`，任意一座是 `Player` 即返回 true（复用 `firstFreeSeatExcept` 的入口）。
- 命中后 `RideBindManager` 走 `MaidAirCombat.holdHere`（目标 = 载具当前位置，dy=0）。

### ③ home 悬停/航迹（两树 `RideBindManager`）

- 无敌人分支新增 `else if (patrolFlight) air = holdHere(...)`：home + 有效航迹但这一拍算不出
  航迹点时**原地悬停**，绝不回落到"跟随主人"。
- 无航迹时仍走 home 闸的 `holdHere`（原本就有的悬停档）。

### ④ 飞过去（两树 `MaidFlightFollowBehavior`、`MaidTravelTarget`）

- canUse 最前面先取 `travel`：`if (!cfg() && !travel) return false;`——显式命令绕过总开关。
- `ownFlightBusy`、`BridgeUpBehavior.isTaskOccupied`、`threatNearby` 都加 `!travel`：
  玩家点了就飞；到达后战斗链路自己重新起手。
- `MaidTravelTarget.aim`：**空中**按 4 格判到达，**站在地面**按 1 格——避免"下单即到点"。

## 验证

- 两树编译 `EXITCODE=0`（Forge 494 源 / Neo 499 源）。
- 14 项护栏 `FAIL 0 / WARN 0`（新助手注释块按 8 行上限压缩；无新文件/无新开关）。
- 打包 + 部署 4 实例 `match=True`。
- 四套专服回归 PASS：`test_server 1201`、`test_server neoforge1211`、
  `test_maid_load 1201`、`test_maid_load neoforge1211`。
- Forge jar：18,183,907 字节，SHA256
  `857619ABD9D8EA0214BBA2D40AAD19FC2CA9A99A65EB14F2855AD320447D7A64`。
- Neo jar：18,245,136 字节，SHA256
  `B28FFC0CB604C28504B5465D1950B06939D388B7F8C5FE9F32FA8150B8907B74`。
- **反证**：
  - ①"一开始没料也会播"：`findStation == null` 分支现在无条件调 `notifyNoIngredient`；
    `pickFurnaceKind == NONE` 是播报的充要条件，30 秒限频防连播。
  - ② SWB `addPassenger` 字节码：只有 `orderedPassengers.set(...)`，无 `super.addPassenger`
    → 原版 `getPassengers()` 必空；`getNthEntity(i)` 实证返回 `orderedPassengers.get(i)`。
  - ③ 无航迹时 `effective == null` → `patrolFlight == false` → home 闸 `holdHere`；
    有航迹时 `patrolFlight == true`，无点也 `holdHere`，代码里已没有任何 home 女仆走 follow 的路。
  - ④ `travel` 在 cfg 闸之前求值；软闸全部带 `!travel`；地面到达半径从 4 缩到 1。

## 边界

- ① 播报仍然只针对**原料**，燃料不足那条线（`cook_no_fuel`）不变；30 秒限频是每只女仆一份。
- ① 有料但附近没炉子时不播报（`pickFurnaceKind != NONE`），避免"有肉还讨肉"。
- ② 只修"机上有人"的识别；固定翼降落链路、接敌档、地面载具逻辑不变。
- ③ home 仍以"航迹生效"为前提沿轨；未绑/未闭环/跨维度时不生效，走悬停。
- ④ 显式「飞过去」仍要求：她能飞（鞘翅 + 烟花/羽扇/法术/激流三叉戟）、不在睡/骑/坐、
  不在自保中；这些是"物理上飞不了"或安全闸，不与"玩家命令"冲突。
- 版本号仍为 **1.3.3**，同号重打；发布素材等验收后再写。

## 验收

1. **无料播报**：背包只放燃料（如煤炭、无任何生食/矿物），让女仆做烧制任务：应播
   「没有能烧的原料了…」，且 30 秒内不重复。
2. **烧完最后一份**：只放一份生肉，烧完后应立刻播同一句（不再需要"喂不进料"的空转）。
3. **换炉静默**：生肉 + 铁矿，先烧肉再烧矿：换炉过程不应播报。
4. **副驾悬停**：女仆开直升机、主人坐副驾、**未绑航迹**：直升机应原地悬停，不再持续爬升；
   接敌时照常绕圈。
5. **home+航迹**：女仆 home 模式 + 直升机 + 闭环航迹：应沿航迹飞；把航迹删掉/取消连接后
   应原地悬停，不再跟随主人。
6. **飞过去（未起飞拴绳）**：武装拴绳绑住地面上的空袭女仆，选一个远处目标点按「飞过去」：
   她应起飞飞向该点；即使飞行跟随总开关关着、附近有怪，也应执行。
