# R1115 鞘翅插槽识别取证 + 挖矿/伐木搭方块开关

## 排班

- **跑道**：① A 取证/诊断（现场报"鞘翅插槽里的鞘翅不被空袭/飞行跟随识别"；先用探针在
  Forge 1.20.1 与 NeoForge 1.21.1 两个测试实例上分别复现判定链）；② C 功能（新配置开关，
  默认保持现状）。
- **开工前读过**：`MaidFlightKit.hasElytra/equip/takeElytra/isElytraLike/elytraDiagnostic`、
  `CuriosElytraCompat.findElytra/takeElytra/collectAll`、`R994`（1.20.1 神秘遗物鞘翅
  player 闸的既有结论）、`MaidWorkBehavior.takeBuildBlock/notifyNoBuildBlock`、
  `MaidMineBehavior` 与 `MaidWoodBehavior` 的三处搭方块入口、`MaidPlaceGuard`、
  `MaidSmartConfig` 的 `[mine]` 段与 `PromaidConfigScreen` 的挖矿页、`ARCHITECTURE-SWITCHES.md`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 取证：两套加载器的饰品栏鞘翅判定 | Forge 1.20.1（Curios 5.14.1 + 鞘翅插槽 6.4.4）与 Neo 1.21.1（Curios 9.5.1）实测 `hasElytra=true`、`equip` 能把槽里的鞘翅穿到胸甲槽 | ☑ |
| 1 | 诊断补齐：槽里有东西但被拒时不再显示成"空" | 诊断行逐件列出槽位=物品id + 女仆/主人两处 `canElytraFly` | ☑ |
| 2 | 移除临时探针（命令、反射调试方法、注册、脚本） | 两树源码无 `MaidCuriosProbeCommand`/`debugDump`/`debugPut` | ☑ |
| 3 | 新开关 `mine.pillarEnabled`（默认开） | 关掉后挖矿/伐木不再垫脚/搭桥，且不播"没材料"气泡 | ☑ |
| 4 | 面板行 + 中英译文 + 生成物 | 面板多一行「挖矿/伐木搭方块」；`panel_coverage.txt`、`ARCHITECTURE-SWITCHES.md` 重生成 | ☑ |
| 5 | 编译/护栏/打包/部署/测试/轮次文档 | 两树 EXITCODE=0；14 护栏 FAIL 0；四套测试 PASS；4 实例 match=True | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者，见文末「验收」。

## 需求 / 来源

> 「鞘翅插槽中的鞘翅无法被空袭/飞行跟随识别」
> 「https://www.mcmod.cn/class/3923.html 顺便给女仆挖矿砍树的搭方块功能加入开关」

第一条附了「鞘翅插槽（Elytra Slot）」的模组页；第二条是本轮的功能项。

## 评估

### ① 鞘翅插槽（取证结论）

- **两套加载器都实测通过**（临时探针 `/maid_smart curios_probe <女仆> minecraft:elytra`，
  跑完即删）：
  - Forge 1.20.1（`1.20.1-Forge_47.4.10`，Curios 5.14.1、鞘翅插槽 6.4.4）：
    女仆的 Curios 处理器是 `CurioInventoryCapability$CurioInventoryWrapper`，槽组含 `back`；
    把原版鞘翅写进 `back` 后 `hasElytra` 由 false 变 true；`equip()` 返回后胸甲槽 =
    `1 elytra`、`back` 变空（即"取出并穿上"这条链路是通的）。
  - NeoForge 1.21.1（`1.21.1-NeoForge_21.1.250`，Curios 9.5.1、鞘翅插槽 9.0.2）：同样结论。
- **为什么"能取出来穿上"才重要**：鞘翅插槽给**玩家**的滑翔是靠 Caelus 飞行属性 + 只在
  `PlayerTickEvent` 里加的那一份修饰符（反编译 `ElytraSlotForgeMod#playerTick` 实证），
  **女仆身上没有这条**。女仆要滑翔，只能把鞘翅穿到胸甲槽、走原版
  `LivingEntity.updateFallFlying` 的闸门——这正是 `equip` 做的，所以判定链与它同源。
- **因此仍会"不认"的情形只有一种**：物品对女仆 `canElytraFly` 为 false（典型是写死
  `instanceof Player` 的模组鞘翅，例如 R994 取证的 1.20.1 神秘遗物）。这类物品穿上也
  滑不起来，认了只会造出"起飞即坠落"的假齐备，维持不认（与 R994 的红线一致）。
- **旧诊断的缺口**（本轮补的）：`describe()` 只回"有 1 件/空"，一件因上述原因被拒的鞘翅
  在日志里与空槽**完全一样**，现场无法区分"没扫到槽"与"扫到了但物品不合格"。现在
  `elytraDiagnostic` 会追加 `明细=饰品[back]0=物品id(原版鞘翅=…，canElytraFly=女仆…，…)`，
  并在"对玩家 true、对女仆 false"时直接注明该物品的滑翔写死给玩家。
- **结论**：普通鞘翅（含原版与任何对女仆可滑翔的模组鞘翅）放进鞘翅插槽后可被识别并装备；
  若现场仍不认，请把 `[空袭装备]` 那行的 `明细=` 发来——它会指名物品与两处判定结果。

### ② 挖矿/伐木搭方块开关

- 挖矿与伐木共用一个基类 `MaidWorkBehavior`，两边的搭高/搭桥/垫台阶**全部**经
  `takeBuildBlock(maid)` 取料（每边 3 处调用，共 6 处）。因此开关只需加在这一个闸口，
  不存在"某一条支路漏关"。
- 关掉后 `takeBuildBlock` 返回 null，现有链路自然退回"走路 / 换目标"，不需要改任何一条
  行为分支；同时 `notifyNoBuildBlock` 静默，避免误报"背包里没有能搭的方块"。
- 语义边界：这个开关**只管挖矿与伐木**。搭路（`BridgeUpBehavior`）与自保搭高
  （`SelfPreservationBehavior`）不走 `MaidWorkBehavior`，仍受各自开关与
  `MaidPlaceGuard` 的统一闸口约束，不受本开关影响。
- 默认 **开**（保持现状），所以不需要 `OFF_ACK` 白名单。

## 改动

### ① 诊断（两树）

- `MaidFlightKit.elytraDiagnostic`：新增 `curiosDump(maid, owner)`，用
  `CuriosElytraCompat.collectAll` 逐件列出饰品栏内容，复用 `describeElytraCandidate`
  （物品 id + 原版鞘翅 + `canElytraFly(女仆)`，必要时追加"只给玩家"提示）。
- 删除临时探针：`command/MaidCuriosProbeCommand.java`、两树 `ProMaidExtension` 的注册行、
  `CuriosElytraCompat.debugDump/debugPut`。

### ② 开关（两树）

- `MaidSmartConfig`：`[mine]` 段新增 `MINE_PILLAR_ENABLED`（键 `pillarEnabled`，默认 true），
  译文键 `config.promaid.mine.pillarEnabled`。
- `MaidWorkBehavior.takeBuildBlock`：开关关 → 直接返回 null（挖矿/伐木共 6 处入口统一生效）。
- `MaidWorkBehavior.notifyNoBuildBlock`：开关关 → 不播"没材料"气泡。
- `PromaidConfigScreen` 挖矿页：在「骑乘/坐下中禁止搭方块」下方新增一行
  「挖矿/伐木搭方块」。
- 中英译文（4 个 lang 文件）各补一条。
- 重生成：`promaid_src*/assets/promaid/panel_coverage.txt`、`ARCHITECTURE-SWITCHES.md`。

## 验证

- 两树编译 `EXITCODE=0`（Forge 494 源文件 / Neo 499 源文件）。
- 14 项护栏 `FAIL 0 / WARN 0`（`check_arch` 回到基线 184，无新文件；`check_preset`：
  新开关默认 true，不进 `OFF_ACK`；`check_i18n_keys`：新键两树中英均有译文；
  `check_panel_coverage`：620 / 630 覆盖，与重生成结果一致）。
- 探针取证（跑完已删）：Forge 与 Neo 各一轮
  `hasElytra=false → put=true → hasElytraAfter=true → equip 后 chest=elytra、back=air`。
- 打包 + 部署 4 实例 `match=True`；四套专服回归 PASS（1201 / neoforge1211 × server / maid_load）。
- Forge jar：18,162,808 字节，SHA256
  `35CED7ADB0FFDC34752D8E88E4DC616E832D1E7BA38F8ACAE11B7E2781406598`。
- Neo jar：18,224,030 字节，SHA256
  `68AAC29CC294718ED14C30FCAF59CDC26F2BAEDD07FEDA6E63BF83C56B98DFBB`。
- **反证**：
  - ①"不是没扫到槽"：探针的 `before` 行直接打印出 `[back n=1 0:0 air]`，`put` 后变成
    `0:1 elytra` 且 `hasElytraAfter=true`；`equip` 后 `back` 回到 air、胸甲槽为 elytra。
  - ②"关掉后真的不搭"：`takeBuildBlock` 是两条链路唯一的取料口（6 处调用点），
    返回 null 后所有放置分支都不会走到 `level.setBlock`；同一开关同时静默了误报气泡。

## 边界

- ① **不改变识别口径**：`isElytraLike` 仍是"原版鞘翅 或 `canElytraFly(女仆)`"，不为名字里
  带 elytra 的物品开后门，也不替外部模组的 player 闸施加推力（R994 的既有取舍）。
- ① 只增强诊断文本；普通鞘翅在鞘翅插槽里的行为与旧版一字不差。
- ② 开关**只覆盖挖矿与伐木**；搭路、自保搭高、蓝图、指标石、插火把、`smart_place` 均不受影响。
- ② 关掉后她仍会走动/挖掘/换目标；不会因为"搭不了"卡死，也不会反复播报缺材料。
- 版本号仍为 **1.3.3**，同号重打。

## 验收

1. **鞘翅插槽（普通鞘翅）**：把原版鞘翅放进女仆的饰品栏后槽 → 开空袭：她应正常取出穿上、
   起飞；`logs/promaid.log` 搜 `[空袭装备]` 可见 `饰品栏=有 1 件 明细=饰品[back]0=minecraft:elytra(...)`。
2. **鞘翅插槽（仍不认时）**：把 `[空袭装备]` 那行发来——`明细=` 会给出物品 id 与
   `canElytraFly=女仆false`；若同时出现"它对玩家返回 true"的提示，即为 R994 那类
   只给玩家滑翔的物品，设计上不支持。
3. **新开关**：配置面板 → 挖矿 → 关掉「挖矿/伐木搭方块」→ 让女仆挖一处够不着的高矿 /
   砍高处的树：她只走近并换目标，不再垫方块，也不再播"背包里没有能搭的方块"。
4. **开关关掉不影响搭路/自保**：同一存档里触发搭路（主人站高处）与自保搭高：两者照旧垫方块。
