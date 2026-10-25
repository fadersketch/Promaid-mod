# R1059 重锤贴身才猛击 + 飞行跟随消耗开关合并

## 排班（开工前先填，硬门槛 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug + B 配置整合 —— 两树镜像（1.20.1 Forge + 1.21.1 NeoForge）。
- **开工前读过**：`MaidMaceSmashBehavior`、`MaidCombatTacticsBehavior.meleeTick`、
  `MaidFlightFollowBehavior.boost`、`ProMaidMod` 默认值修复、`MaidSmartConfig` 飞行跟随段、
  `PromaidConfigScreen` 飞行跟随行。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 重锤猛击只在贴身 2 格触发 | 2 格外走平A，不再每 3 秒起跳 | ★ |
| 2 | 重锤不吃通用跳劈 | 单兵战术持重锤时不触发自己的跳劈 | ★ |
| 3 | 三项飞行消耗合并为一 | 面板/配置只剩「是否消耗飞行物」 | ★ |
| 4 | 三项耐久/数量统一受控 | 关掉后烟花、三叉戟、鞘翅都不掉 | ★ |
| 5 | 两树编译与护栏 | 两树 `EXITCODE=0`；14 护栏 `FAIL 0` | ★ |
| 6 | 双端回归与装机 | 四套测试 PASS；4 个目标 `match=True` | ★ |
| 7 | 交开发者验收 | 重锤以平A为主；飞行跟随只有一个消耗开关 | ☐ |

## 需求 / 来源

- 开发者反馈 1：“女仆使用重锤时不聪明了，如果手长时都是用重锤平A打人，而不是持续的使用
  风弹起跳下落。仙法门只有在敌人贴的很近的时候才会用。”
- 开发者反馈 2：“空袭的飞行跟随里，将消耗烟花火箭、三叉戟合并为一个开关，名称就叫是否消耗
  飞行物，此开关控制飞行时是否消耗飞行物和飞行物的耐久（烟花数，三叉戟，壮丽鞘翅这些
  可驱动物是否产生损耗）。”

## 评估

- **重锤**：`MaidMaceSmashBehavior` 的触发距离默认 3 格；`MaidCombatTacticsBehavior` 的近战
  走位停在 2.6 格、贴脸还会后撤到 3.5 格。两边一配合，目标长期落在 2.6~3.0 格，猛击每
  3 秒冷却一好就起跳——玩家看到的就是“不用重锤平A、只会风弹起跳下落”。
  另外，单兵战术的通用跳劈对非斧武器（包括重锤）也生效，等于第二套起跳源。
- **飞行跟随**：原配置是三个独立开关 `flightFollow.firework` / `.elytra` / `.trident`，
  面板也是三行；代码里三个 `cfg*()` 分别控制烟花数量、鞘翅耐久、三叉戟耐久。

## 改法

### 1. 重锤只贴身猛击（两树）

- `COMBAT_MACE_TRIGGER_RANGE` 默认 3 → **2**，可选范围压到 **1~2**；行为里再加一道
  `MACE_LEAP_MAX_RANGE = 2.0` 兜底，老 toml 写着 3 也按 2 处理。
- 2 格外目标由 TLM 正常近战结算（女仆手长，2.6~3 格仍能挥到），不再起跳。
- `MaidCombatTacticsBehavior.meleeTick`：主手是重锤时**跳过通用跳劈**
  （起跳猛击统一交给 `MaidMaceSmashBehavior`，避免两套跳跃叠加）。
  Neo 新增 `MaidMaceSmashBehavior.isMace(ItemStack)`；Forge 复用已有的注册名判据
  （`trials:mace`）。
- 猛击仍然要求“重锤 + 风弹”并消耗 1 枚风弹；冷却 60 tick 不变。

### 2. 飞行跟随消耗开关三合一（两树）

- 删除 `FLIGHT_FOLLOW_FIREWORK` / `FLIGHT_FOLLOW_ELYTRA` / `FLIGHT_FOLLOW_TRIDENT`，
  新增 `FLIGHT_FOLLOW_CONSUME`：
  - 配置键 `flightFollow.consume`，默认 **true**，译名「飞行跟随·是否消耗飞行物」。
  - 开：烟花每次补推扣 1 枚、激流三叉戟每次推进扣 1 点耐久、鞘翅每 20 tick 扣 1 点耐久。
  - 关：这一趟仍要求背包里有可飞道具（烟花/羽扇/位移法术），但三样都不产生损耗。
  - 只管飞行跟随；空袭的起飞/抬升/俯冲照旧消耗。位移法术本来不消耗物资。
- `MaidFlightFollowBehavior`：三个 `cfg*()` 合并成 `cfgConsume()`，日志改成一行
  「消耗飞行物=开/关」。
- `ProMaidMod` 默认值修复改为只检查 `FLIGHT_FOLLOW_CONSUME`。
- 面板三行合并为一行「飞行跟随·是否消耗飞行物」；旧的三个 lang 键删除，新增
  `config.promaid.flightFollow.consume`（中/英四份 lang）。
- `check_preset.py` 的 DIY 关闭基线随配置项减少同步：Forge 119→117、Neo 122→120。
- 已重新生成 `panel_coverage.txt` 与 `ARCHITECTURE-SWITCHES.md`。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidMaceSmashBehavior.java`
- `promaid_src/com/maidsmart/combat/MaidCombatTacticsBehavior.java`
- `promaid_src/com/maidsmart/combat/MaidFlightFollowBehavior.java`
- `promaid_src/com/maidsmart/config/MaidSmartConfig.java`
- `promaid_src/com/maidsmart/config/PromaidConfigScreen.java`
- `promaid_src/com/maidsmart/ProMaidMod.java`
- `promaid_src/assets/maid_smart/lang/{zh_cn,en_us}.json`
- `promaid_src/assets/promaid/panel_coverage.txt`
- `promaid_src_neo/...` 同名文件的镜像
- `tools/verify/check_preset.py`、`ARCHITECTURE-SWITCHES.md`
- 无新 mixin、无新反射探针、无新 Java 类。

## 验证

- Forge `compile_promaid`：`EXITCODE=0`；Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`（含 i18n / panel coverage / preset / workflow）。
- `test_server 1201`：PASS；`test_server neoforge1211`：PASS。
- `test_maid_load 1201`：PASS；`test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例全部 `match=True`。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,091,171 字节，
    SHA256 `FB925007649AA1BAB93574569050303CDF442F73891B5B81411AD59C6A8B062F`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,179,256 字节，
    SHA256 `79B72E9962FA7EC029AF296ACB9D0A4028C9E09D9B7F4532245F3944C4777542`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 重锤：让女仆持重锤打一只怪。目标在 2~3 格时她应正常挥锤，不应每 3 秒起跳；
     只有贴到 2 格内才偶尔风弹起跳砸落。日志搜「重锤门」应看到
     `超出贴身距离（>2格，改用重锤平A）`，而不是一直 `超出起跳距离`。
  2. 风弹数量：2 格外平A期间风弹数量不应减少；只有真正起跳猛击时才扣 1 枚。
  3. 飞行跟随面板：原三行应只剩「飞行跟随·是否消耗飞行物」一行。
  4. 关掉该开关后飞一趟：烟花数量、激流三叉戟耐久、鞘翅耐久都不应变化；
     重新打开后三样照原版消耗。空袭的消耗不受这个开关影响。

## 边界（没动什么）

- 不改重锤猛击的伤害公式、风弹消耗、冷却、起跳初速、落地缓冲。
- 不改 2 格内猛击的行为；只把“多远才起跳”从 3 收到 2。
- 不改飞行跟随的起手/收手距离、燃料顺序（羽扇 → 烟花 → 三叉戟 → 位移法术）、
  位移法术的冷却与推力。
- 旧配置里的 `flightFollow.firework` / `.elytra` / `.trident` 不再读取；新键
  `flightFollow.consume` 默认开。旧档若曾关过某个省料项，需要在新开关里重新关一次。

## 台账

- 本轮不清债；未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
