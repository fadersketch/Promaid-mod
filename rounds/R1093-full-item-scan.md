# R1093 参战与任务物品扫描统一

## 排班

- **跑道**：物品扫描口径统一（两树镜像），R1092 的延续。
- **为什么这轮先做这个**：模组任务进池只扫主手/背包，法术袋放饰品栏、材料放
  精妙背包时会被判成"没有"；需要把判定侧与取物侧的口径对齐。
- **开工前读过**：`AutoCombatPools`（候选池与武器闸）、`CombatTaskCompat.prepareSwitch`、
  `MaidExtraContainer`（额外容器公开 API）、各任务/自保/飞行的物品判定位点。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 新增只读全量扫描入口 | `any` 覆盖双手/背包（含压缩盒）/额外容器 | ☑ |
| 2 | 参战候选池改走全量扫描 | 主手/背包/额外容器一致 | ☑ |
| 3 | 法术任务按 `maidHasSpells` 判定 | 饰品栏/附属数据也能进池 | ☑ |
| 4 | 换任务预检补搬额外容器武器 | 池里认了就能装上 | ☑ |
| 5 | 任务/自保/飞行的判定与取物对齐 | 判定 `contains`、取物 `pull` | ☑ |
| 6 | 编译、护栏、重打、部署、测试 | 两树 `EXITCODE=0`；14 护栏 `FAIL 0`；四套测试 PASS | ☑ |

## 一、新增的统一入口

`MaidExtraContainer.any(maid, filter)`：主手 → 副手 → 她自己的背包
（压缩盒延伸格由 mixin 追加）→ TLM 额外容器（精妙背包 / 旅行者背包，只读探针）。
用于"有没有这件东西"的判定；真正取物仍走 `pull`。

## 二、参战侧

- `hasWeaponForTask` / `hasNonVanillaWeaponForTask` 改走统一扫描：主手 +
  背包（含压缩盒）+ 额外容器。副手与饰品栏不计入"能打"——两者不会被自动换到
  主手，计进来只会让她被切到打不出伤害的任务。
- 法术任务不再先过武器闸：它的 `isWeapon` 恒 true，真正该问的是
  `maidHasSpells`（覆盖主手/副手/背包/饰品栏/附属数据）。模组任务那道
  "非原版物品"闸对法术任务也跳过——`maidHasSpells` 已经证明持有模组法术装备。
- `prepareSwitch` 对非法术任务先从额外容器搬一件匹配武器进背包，再走官方
  换装：否则会出现"候选池认了、预检 `MISSING_REQUIRED_ITEM` 永远切不过去"。

## 三、任务与自保侧（已对齐）

| 位置 | 判定 | 取物 |
|---|---|---|
| 建造材料 `BlueprintMaterials` | `countMaterial` 前先 `pull` 同类材料；`hasBucketTool` 走 `any` | `consumeOne` 先 `pull` 1 件（含岩浆桶） |
| 指标石补支撑 `IndexStoneBuildBehavior` | — | `takeOneFor` 先 `pull` 1 件 |
| 炸弹 `BombItems` | `has/hasBed/hasTnt/hasIgniter` 走 `any` | `takeFirstMatch(!dryRun)` 先 `pull` 1 件 |
| 落地水 `WaterClutchBehavior` | `hasItem` 走 `any`（桶只是钥匙，不消耗） | — |
| 灭火 `SelfPreservationBehavior.hasWaterBucket` | 走 `any`（水只是钥匙，不消耗） | — |
| 重锤风弹 `MaidMaceSmashBehavior` | `hasWindCharge` 额外容器 `contains` | `consumeWindCharge` 先 `pull` 1 枚 |
| 羽扇 `TwilightFanKit` | `hasFan` 额外容器 `contains` | `boostGlide` 先 `pull` 再 `findFan` |
| 飞行远程武器 `MaidFlightKit` | `hasUsableRangedWeapon` 额外容器 `contains` | 既有 `takeFromBackpack` 已 `pull` |

## 四、刻意不做的（附理由）

- **副手/饰品栏不计入"能打"**：TLM 换装只吃主手与背包，计进来是虚假战力；
  法术装备另有口径（施法确实读副手/饰品栏）。
- **保命图腾 `hasDeathSaveItem`**：图腾混入只从双手/背包/饰品栏消耗，额外容器里的
  图腾救不了命；算进来会让"有保命物品"的判定失真。
- **重锤 `hasMaceSomewhere`**：该行为要求主手就是重锤，额外容器里的重锤不能直接挥。
- **伐木/挖矿 `countBlocking`**：扫的是方块射线，不是物品。
- **盾牌数量**：仅诊断用。
- **魂符**：扫的是主人背包，不是女仆。
- **`findRiptide` / `findFan`**：纯读取（就绪/气泡/日志用），不允许副作用；
  需要真取时走 `fetchRiptide` / `boostGlide`，判定侧用 `contains`。

## 改动文件

- 两树 `combat/AutoCombatPools.java`、`combat/CombatTaskCompat.java`
- 两树 `tool/MaidExtraContainer.java`
- 两树 `build/BlueprintMaterials.java`、`build/IndexStoneBuildBehavior.java`
- 两树 `combat/BombItems.java`、`combat/WaterClutchBehavior.java`、
  `combat/SelfPreservationBehavior.java`、`combat/MaidMaceSmashBehavior.java`、
  `combat/TwilightFanKit.java`、`combat/MaidFlightKit.java`

## 验证

- Forge / Neo 编译均 `EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`（注入点 285）。
- 专服回归：`test_server.py 1201` PASS、`test_maid_load.py 1201` PASS、
  `test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS。
- Forge jar：18,139,131 字节，SHA256
  `A42363C61BC034913CF5D70A5DF7C7D9D2650E0096086E4F03481AF1745ED67A`。
- Neo jar：18,199,082 字节，SHA256
  `B33D5592AEBFC15D6263D2F81273B642850DDB8DA075F08629E373C1EB3A2FF7`。
- 已部署 4 实例，`match=True`。

## 边界

- 额外容器只在"Curios 在场 + TLM 女仆饰品开启 + 背包装在饰品槽"时存在；不满足时
  `any`/`pull` 与旧版完全一致（只扫双手/她自己的背包）。
- 判定侧用 `contains` 是"取出 1 件立刻放回"的只读探针，正常路径净零；放回失败时
  按既有兜底链（原容器 → 她自己背包 → 落地）处理，绝不吞物品。
- 进池变宽后，最终能否切换仍由 `prepareSwitch` 把关；装不上就不切，不会卡在
  打不出伤害的任务上。

## 验收

1. 法术袋只放饰品栏、主手/背包全是原版物品：自主战斗仍能切进法术任务。
2. 模组武器只在精妙背包：候选池认她"有武器"，参战预检把它搬进背包并装上。
3. 建造材料/指标石方块只在精妙背包：建造能正常取材，不再报"缺材料"。
4. 炸弹/风弹/羽扇/水桶只在精妙背包：对应任务与自保行为照常触发并取用。
5. 不装精妙背包/旅行者背包：所有行为与旧版一致。
