# R1016 1.21.1 近战空袭的重锤链路迁移到 1.20.1（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug + B 小功能（补齐 R1015 重锤移植遗漏的“空袭中使用重锤”能力；
  不新增玩法框架，只把 1.21.1 已有链路对齐到 Forge）。
- **开工前读过**：`rounds/R1015-config-panel-cleanup-mace-port.md`、两树
  `MaidFlightCombatBehavior.java`、`MaidFlightKit.java`、`MaidFlightCombatTask.java`、
  `MaidMaceSmashBehavior.java`、`WaterClutchBehavior.java`、`MaidPlaceGuard.java`、
  `MaidChunkLoadManager.java`、`MaidTeleportPreserveMixin.java`、`SelfPreservationBehavior.java`、
  `MaidSmartConfig.java`、`PromaidConfigScreen.java`、`GuideChaptersFlight.java`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 空袭重锤伤害 | Forge `MaidFlightCombatBehavior` 记录本轮最高 Y；主手 `trials:mace` 时按最高点强制写 `fallDistance`，结算三段加成 + 附魔 + 击退/火焰/荆棘 + 猛击粒子/音效；结算完还原真实值 | ☑ |
| 1 | 空袭重锤换装 | `MaidFlightKit.equip` 近战分支优先从背包取 `trials:mace`；`MaidToolAutoEquip.isMeleeWeapon` 按注册名兜底识别重锤 | ☑ |
| 2 | 空中联动 | 重锤跃起/空袭期间：禁自动传送（TLM 跟随、跨维跟随、同维拉回、自保归位）、禁搭方块、落地强制缓冲；静态表按女仆/服务端清理 | ☑ |
| 3 | 配置与手册 | 新增 `airRaid.smashMinFall`（默认 1.5）+ 面板行 + 中英译文；手册补“空袭这一锤分两种武器”和跨版本说明 | ☑ |
| 4 | 两树编译 | `EXITCODE=0` | ☑ |
| 5 | 14 护栏 | `FAIL 0 / WARN 0`；面板覆盖、开关清单同步 | ☑ |
| 6 | 双 jar + 专服回归 | Forge / Neo 构建成功；`test_server` 两套 + `test_maid_load` 均 PASS；Forge 额外带 Trials 启动 PASS | ☑ |
| 7 | 部署 4 实例 | `match=True` ×4 | ☑ |
| 8 | 更新发布说明 | 仓库 3 份 + 桌面 2 份追加本轮条目 | ☑ |
| 9 | 开发者实机验收 | 近战空袭持试炼重锤时收翅俯冲砸出下落加成；飞得越高伤害越高；无重锤时仍是暴击 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法：①给女仆背包放鞘翅 + `trials:mace` + 烟花，切「近战空袭」，
  确认她把重锤换到主手；②放一只怪，观察她爬升后收翅俯冲，命中时有一圈烟尘与重锤音效；
  ③从不同高度对比伤害，确认高处砸下来明显更痛；④把重锤拿走，确认仍按原版暴击 ×1.5；
  ⑤配置面板「战斗与自保 → 空袭数值 → ② 收翅俯冲」能看到「猛击下落加成门槛」。

## 需求 / 来源

- 开发者原话（本轮）：「那么重锤的相关空袭战术呢？」
- 背景：R1015 只迁了地面起跳猛击；1.21.1 的「近战空袭」本身以重锤为核心武器（鞘翅 + 重锤 + 烟花），
  Forge 侧仍按普通近战暴击结算，等于空袭里完全吃不到重锤的下落加成。

## 评估

1.21.1 的重锤空袭不是“另一套状态机”，而是同一套 `MaidFlightCombatBehavior` 在收翅俯冲那一击里换算法：
- 滑翔会把 `fallDistance` 钳在 1.0，收翅后自由落体也来不及攒到 1.5 以上；Neo 侧用 `MAX_Y` 记录本轮最高点，
  结算前临时把“最高点 − 当前 Y”写进 `fallDistance`，让重锤的猛击判定/加成/风爆都按真实落差算。
- Forge 的 Trials 回移植重锤同样只读 `fallDistance`，但它的玩家路径由 `CriticalHitEvent` 处理，女仆不适用；
  因此沿用 R1015 的做法，在 `hitOne` 里显式复刻：攻击力 + 附魔 + `maceFallBonus`，再补击退/火焰/荆棘/耐久。
- 空袭的“重锤风爆”附魔若存在，会走 `EnchantmentHelper` 的后效；强制写入的 `fallDistance` 在结算期间可见，
  `finally` 还原，不影响后续摔落判定。
- 装备位：Neo 的 `equip()` 在近战分支优先从背包找重锤；Forge 照抄，并让自动装备的 `isMeleeWeapon` 认注册名，
  避免 Trials 重锤没有 `ATTACK_DAMAGE` 修饰符时模式根本不激活。

## 改动文件

- `promaid_src/com/maidsmart/combat/MaidFlightCombatBehavior.java`：`MAX_Y` / `forcedFallDistance`、
  `hitOne` 重锤分支、猛击粒子/音效、`smashMinFall()` 配置访问器、起飞点重置最高点。
- `promaid_src/com/maidsmart/combat/MaidFlightKit.java`：近战空袭优先取 `trials:mace`。
- `promaid_src/com/maidsmart/task/MaidToolAutoEquip.java`：`isMeleeWeapon` 按注册名兜底认重锤。
- `promaid_src/com/maidsmart/combat/MaidMaceSmashBehavior.java`：公开 `isMace`、`isAirborne`、
  `requestForcedClutch` / `consumeForcedClutch`、`smashSound`、`forget` / `clearAll`。
- `promaid_src/com/maidsmart/combat/WaterClutchBehavior.java`：跃起让位 + 落地帧强放水/雪。
- `promaid_src/com/maidsmart/tool/MaidPlaceGuard.java`、`follow/MaidChunkLoadManager.java`、
  `mixin/MaidTeleportPreserveMixin.java`、`combat/SelfPreservationBehavior.java`：空中禁搭/禁传联动。
- `promaid_src/com/maidsmart/memory/AiMemoryManager.java`、`ProMaidExtension.java`：静态表清理。
- 两树 `config/MaidSmartConfig.java`、`config/PromaidConfigScreen.java`、`assets/maid_smart/lang/*.json`：
  Forge 新增「猛击下落加成门槛」；Neo 原本已有。
- 两树 `guide/GuideChaptersFlight.java`、`assets/promaid/i18n/en_us.json`：空袭重锤说明与跨版本措辞。
- `promaid_src/assets/promaid/panel_coverage.txt`、`ARCHITECTURE-SWITCHES.md`：重新生成。

## 验证

- Forge / Neo 编译：`EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`；Forge 面板覆盖 597 个字段；`check_guide_i18n` 缺译 0；
  `check_preset` Forge 110 / Neo 111 与基线一致；`check_workflow` 最新轮次 R1016。
- 双 jar：Forge 18,056,378 字节（SHA256 `a9d6f611…4dba43`）；
  Neo 18,090,876 字节（SHA256 `086fdffc…eec88b5`）。
- 专服：`test_server.py 1201`、`test_server.py neoforge1211`、`test_maid_load.py neoforge1211` 均 PASS；
  Forge 测试服额外挂载 Trials 2.3.2 启动到 Done，无类加载/注册表错误（测完已移除）。
- 部署：4 实例 `match=True`。

## 边界

- 只识别 `trials:mace`；其他模组的“重锤”不会触发（避免把普通武器误判）。
- 没有安装 Trials 时，空袭仍按“任意近战武器 + 原版暴击 ×1.5”工作，不会因缺物品而失效。
- 本次不含 1.21.1 的「二号位重锤猛击」（玩家吊在女仆下方、由拴绳链路代记坠落距离）——那是
  `GunnerTetherManager` + `PlayerMaceRideFallMixin` 的独立功能，不在“近战空袭”这条链路上。
- 空袭重锤的“风爆”附魔取决于 Trials 回移植是否提供该附魔；本模组只负责把 `fallDistance` 在结算瞬间
  写成实际落差，不额外实现风爆。
- 版本号仍为 1.3.3（同号重打），不升号。

## 验收

- 待开发者实机验收：近战空袭持 `trials:mace` 时优先换锤、收翅俯冲有下落加成与猛击特效；
  高空俯冲伤害明显高于贴地扑击；无重锤时仍是暴击 ×1.5；空中不被自动传送/搭方块打断。
- R1015 的配置面板、矿表、名称与地面重锤验收项仍有效；本轮不改动这些路径。
