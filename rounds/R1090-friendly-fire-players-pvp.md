# R1090 女仆友伤：其他玩家跟随 PvP / 枪械弹丸归因 / 同队免伤

## 排班

- **跑道**：缺陷修复（两树镜像）。
- **为什么这轮先做修复**：玩家带女仆用枪械时，队友和怪站在一起会被流弹/AoE 一起打；
  改动集中在友伤总闸与弹丸归因，面小、可立即验收。
- **开工前读过**：`FriendlyFireGuard`（三层伤害事件 / `maidOfDamage` / `isFriendly` /
  仇恨清理）、`AutoCombatTargeting`（玩家不在自动索敌里）、`MaidCanAttackDefenseMixin`
  （行为化例外可把攻击我们的玩家放进可攻击集）、`MaidSmartConfig` 的
  `friendlyFireGuard` 开关、两树 lang 与 `ARCHITECTURE-SWITCHES.md`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 其他玩家按 PvP 属性同步免伤 | PvP 关 → 女仆打不了任何玩家 | ☑ |
| 2 | 同队队友始终免伤 | 主人与目标同队 → 取消 | ☑ |
| 3 | 枪械/火箭弹丸归因到开枪的女仆 | `getOwner()` 是女仆 → 算她头上 | ☑ |
| 4 | 编译、护栏、重打、部署、测试 | 两树 `EXITCODE=0`；14 护栏 `FAIL 0`；四套测试 PASS | ☑ |

## 一、现象与根因

主人免伤一直有效，但**其他玩家**是"非友方"：`isFriendly` 只认主人、同主女仆和
`isAlliedTo`（计分板同队）。枪械射击时，弹丸/爆炸的伤害源要么直接归到女仆头上
（`getEntity` = 她），要么只把"弹丸实体"写进伤害源——两种都会穿过旧闸，于是和怪
站在一起的队友被一起打。

## 二、修法

### 1. 其他玩家跟随「玩家间伤害」(PvP) 同步

`FriendlyFireGuard.isFriendly` 增加玩家分支（主人已在上面单独判过）：

- **PvP 关闭**（`MinecraftServer.isPvpAllowed()` 为 false）→ 任何玩家都算友方：
  近战/横扫/箭矢/法术/爆炸/枪械流弹的三层伤害事件全部取消，仇恨每 2 秒清掉；
- **PvP 开启** → 与主人同队的玩家仍免伤（原版队友免伤口径），其余玩家按原版可交战；
- 取不到 PvP 属性时按"允许"放行，避免误伤闸把正常战斗全掐掉。

1.20.1 的 PvP 是服务端属性 `pvp`（`server.properties`），用
`Level.getServer().isPvpAllowed()`（SRG `m_129799_`，javap 核实过字段链路）；
1.21.1 用 `MinecraftServer.isPvpAllowed()`。

### 2. 弹丸归因

`maidOfDamage` 在「造成者是女仆」「直接实体是女仆」之后补一条：直接实体是
`Projectile` 且 `getOwner()` 是女仆 → 这笔伤害算她的。枪械子弹/火箭经常只把弹丸
写进伤害源，不补这条，PvP 关闭/队友免伤就识别不出来，闸形同虚设。

### 3. 文案与台账

- 配置注释与面板标题改为「主人 / 队友 / 玩家免伤总闸（随 PvP）」，写明 PvP 关闭 =
  女仆也不能打玩家、PvP 开启 = 同队仍免伤；
- 重新生成 `ARCHITECTURE-SWITCHES.md`（配置说明变化会触发该清单过期）。

## 改动文件

- `promaid_src/com/maidsmart/combat/FriendlyFireGuard.java`
- `promaid_src_neo/com/maidsmart/combat/FriendlyFireGuard.java`
- `promaid_src/com/maidsmart/config/MaidSmartConfig.java`
- `promaid_src_neo/com/maidsmart/config/MaidSmartConfig.java`
- 两树 `assets/maid_smart/lang/zh_cn.json` / `en_us.json`
- `ARCHITECTURE-SWITCHES.md`（重新生成）

## 验证

- Forge / Neo 编译均 `EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`（注入点 285）；`ARCHITECTURE-SWITCHES.md` 已重新生成。
- 专服回归：`test_server.py 1201` PASS、`test_maid_load.py 1201` PASS、
  `test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS。
- Forge jar：18,136,806 字节，SHA256
  `1AB84BDC0484BBDCC20059F6157C73CFC2DE0C94B885A7D13E3DB6C1760E7E0E`。
- Neo jar：18,196,583 字节，SHA256
  `2D8F8B22C42B190FF35E7343D9DD6F128D22A3B18295008B2D00B77561392FBC`。
- 已部署 4 实例，`match=True`。

## 边界

- 只拦"能归因到女仆"的伤害（造成者是她、直接实体是她、弹丸 owner 是她、她登记过的
  TNT）。完全不带任何归属信息的第三方爆炸仍无法识别——那类伤害在原版里也认不出是谁。
- PvP 开启且队友**不在同一计分板队伍**时，仍按原版可交战；要绝对免伤，请把队友拉进
  同一队伍（原版队友免伤口径）。
- 主人本人永远免伤，与 PvP 开关无关（原有行为不变）。
- 无主女仆不介入（`MaidScope.owned` 放行），与既有口径一致；关闭
  `friendlyFireGuard` 开关则整条闸回原版。

## 验收

1. 服务器/世界关闭「玩家间伤害」：让女仆用枪对怪堆开火，队友站在怪旁边不掉血、
   不被击退；她自己和主人的伤害判定不变。
2. 打开「玩家间伤害」：把队友放进同一计分板队伍，再开火——队友仍不掉血；
   不同队的玩家按原版会被打到（这是 PvP 开启的预期）。
3. 主人站在弹道上：始终不掉血（原有主人免伤不变）。
4. 关闭 `friendlyFireGuard` 开关：完全回原版，主人也可能被她打到（既有说明不变）。
