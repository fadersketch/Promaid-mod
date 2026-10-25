# R1091 其他玩家的宠物 / 友方单位一并免伤

## 排班

- **跑道**：缺陷修复（两树镜像），R1090 的延续。
- **为什么这轮先做修复**：R1090 只把「其他玩家本人」纳入免伤，他们的狗、猫、
  召唤物、女仆仍会被流弹/AoE 打到；改动集中在 `PetImmunityGuard` 的判定与归因。
- **开工前读过**：`PetImmunityGuard`（`isPetMarked` / `onLivingHurt` / 仇恨清理）、
  `FriendlyFireGuard`（`pvpAllowed` / `maidOfDamage`）、`OwnableEntity` 字节码
  （1.20.1 `m_21805_` / `m_269323_`；1.21.1 `getOwnerUUID` / `getOwner`）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 有玩家主人的单位按玩家口径免伤 | 同队/自己 → 免；PvP 关 → 全免 | ☑ |
| 2 | 枪械弹丸归因复用统一入口 | 弹丸 owner 是女仆也拦得住 | ☑ |
| 3 | 编译、护栏、重打、部署、测试 | 两树 `EXITCODE=0`；14 护栏 `FAIL 0`；四套测试 PASS | ☑ |

## 一、现状与改法

旧版 `isPetMarked` 只保护三类：名字含「玩家宠物」「主人的宠物」、以
`MaidNoAttack` 开头、以及**主人自己**的可驯服宠物。其他玩家名下的宠物/召唤物/
女仆不在名单里——R1090 之后玩家本人免伤了，他们的单位仍会被枪械流弹和爆炸打到。

现在增加一条通用归属判定（`OwnableEntity`，owner UUID 非空即视为有玩家主人）：

- 与攻击女仆的主人**同一个玩家**或**同一计分板队伍** → 免伤；
- **PvP 关闭** → 任何玩家名下的单位一律免伤（与 R1090 的玩家口径同步）；
- **PvP 开启且不同队** → 保持原版可交战（否则 PvP 会变成"只能挨打不能还手"）。

覆盖范围：原版可驯服宠物（狼/猫/鹦鹉/马等）、实现 `OwnableEntity` 的模组宠物与
召唤物、以及其他玩家的 TLM 女仆。名字标记与这条是"或"关系，任一命中即免伤。

## 二、归因统一

`PetImmunityGuard.onLivingHurt` 原来只查伤害源的 `cause` / `direct` 是否为女仆，
枪械子弹常只把弹丸实体写进伤害源 → 认不出开枪的人，宠物闸形同虚设。现在改用
`FriendlyFireGuard.maidOfDamage(source)`（含「直接实体是 Projectile 且 owner 是女仆」
这一条），与玩家免伤走同一个归因口，避免两套口径漂移。

## 改动文件

- `promaid_src/com/maidsmart/combat/PetImmunityGuard.java`
- `promaid_src_neo/com/maidsmart/combat/PetImmunityGuard.java`
- `promaid_src/com/maidsmart/combat/FriendlyFireGuard.java`（`pvpAllowed` 改为包内可见）
- `promaid_src_neo/com/maidsmart/combat/FriendlyFireGuard.java`
- 两树 `config/MaidSmartConfig.java`（宠物免伤说明）
- 两树 `assets/maid_smart/lang/zh_cn.json` / `en_us.json`
- `ARCHITECTURE-SWITCHES.md`（重新生成）

## 验证

- Forge / Neo 编译均 `EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`（注入点 285）。
- 专服回归：`test_server.py 1201` PASS、`test_maid_load.py 1201` PASS、
  `test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS。
- Forge jar：18,136,985 字节，SHA256
  `F532EF7E79C2515B61D271E737308848E3F7A1E7E97FC3C6DDDC661FC7A4793D`。
- Neo jar：18,196,756 字节，SHA256
  `1BF2799E463B9FF9B33A05CF32C8B9B798DFB55AC6DA219BB3149040729668D3`。
- 已部署 4 实例，`match=True`。

## 边界

- 判定的是"有玩家主人的单位"（`OwnableEntity` 且 owner UUID 非空）；野生生物、
  村庄铁傀儡、无主女仆不在此列。
- PvP 开启且不同队时，敌方玩家名下的单位仍按原版可交战——这是有意保留的 PvP 对等；
  想让队友的宠物绝对安全，把队友拉进同一计分板队伍。
- 主人离线时单位仍按"有玩家主人"处理：PvP 关闭一律免伤，PvP 开启且无法比对队伍时
  按原版可交战。
- 关闭 `petImmunity` 开关则整条回原版（名字标记也不再生效，与既有说明一致）。

## 验收

1. PvP 关闭：队友的狼/猫/召唤物站在怪堆里，女仆用枪扫射——它们不掉血、不被点燃。
2. PvP 关闭：另一名玩家的女仆站在旁边，交火时不会被误伤，也不会互相打起来。
3. PvP 开启 + 队友同队：队友的宠物仍然免伤。
4. PvP 开启 + 不同队：对方玩家及其单位按原版可交战（预期行为）。
5. 给任意生物命名「玩家宠物」：仍然免伤（原有名字标记不变）。
