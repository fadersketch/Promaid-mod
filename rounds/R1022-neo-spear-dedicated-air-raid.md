# R1022 1.21.1 长矛空袭单开 + 35° 浅角 + 平地冲锋不再转圈（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复 / 兼容补齐（1.21.1 专属；1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`rounds/R1021-neo-spear-stability.md`、`MaidFlightCombatBehavior.java`、
  `SpearAttackBehavior.java`、`MaidMaceSmashBehavior.java`、`MaidFlightRecall.java`、
  `MaidFlightCombatTask.java`、`ProMaidExtension.java`、`PromaidConfigScreen.java`、
  `MaidSmartConfig.java`；以及实机日志 09:35–09:38。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 实机日志定位 | 确认旧链路每 6.5 秒循环一次 = 突袭超时 100 tick + 起飞 30 tick；确认起飞是「背离 + 62°」，越飞越远、压不下来；确认平地开局一边看目标一边往反方向走，导致原地打转 | ☑ |
| 1 | 空袭单开 | 新增独立 core 行为 `SpearAirRaidBehavior`（优先级 237）与独立配置页「长矛核心」；通用空袭在它接管时整段让位 | ☑ |
| 2 | 35° 浅角 | 新增爬升角 / 俯冲角上限两条配置（默认 35°）；起飞与再次起飞都朝目标抬头，不再「背离 + 62°」 | ☑ |
| 3 | 恢复俯冲 | 爬升到目标上方约 3 格即转浅角接近；高度差 ±2 格且进入点火距离时补水平烟花；最近 8 格不点火 | ☑ |
| 4 | 单开配置面板 | 新增 `SPEAR` 分区页：攻击模式冲锋 + 空袭独立开关 + 五条旧空袭参数 + 两条新角度参数；旧页只留跳转 | ☑ |
| 5 | 平地冲锋不转圈 | `OPENING` / `RETREAT` 段改为面朝移动落点（不再盯目标）；1 秒还拉不开就掉头冲锋 | ☑ |
| 6 | 牵引绳 | `isEngaged` 纳入独立链路；再补「有活目标在 50 格内就不拉回」的状态抖动兜底 | ☑ |
| 7 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0`；配置清单与架构产物同步 | ☑ |
| 8 | 专服回归 | 测试服临时放入 `spearcore-1.21.1-neo-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS，测试后移除 | ☑ |
| 9 | 部署与验收 | 只部署 1.21.1 实例；Forge 源码树与两个 Forge 实例一律不动；待开发者实机验收 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话（本轮）：
  「1.女仆上升后直接失去了俯冲能力，而且飞的斜角太高了。长矛斜角在35°左右即可。对于长矛的空袭是像重锤一样单开
  2.女巫在使用平地使用长矛冲锋的时候不干脆。可以看一下刚刚运行的游戏。有的时候喜欢在原地先转好几圈，然后再冲」
- 追加澄清：
  「我的意思是长矛是和重锤一样单开配置面板和链路处理，不过新链路与通用链路有一定相似而已」

## 评估

1. **上升后失去俯冲**：旧链路收尾后 `faceLaunchDirection` 对地面目标走「背离 + 向上（近战
   62°）」，下一枚烟花把她推得又高又远；进入俯冲段时 `distH` 已超过 20 格，只能平飞回追，
   100 tick 内回不到点火距离 → 超时 → 再背离起飞，形成 6.5 秒循环。日志里 09:38:28/34/41/42/49/52
   的六次「放烟花起飞」与两次 100 格牵引绳都是同一根因。
2. **35° 浅角**：新链路起飞/再次起飞都朝目标抬头 35°；俯冲按高度差取更浅角度、上限 35°。
   爬升到目标上方约 3 格就转接近段，不再一路窜高。
3. **水平点火**：接近段在高度差 ±2 格、距离 ≤ 点火距离且 ≥8 格时补一记水平烟花；8 格内不点火
   （贴脸再点没有意义，还会把目标甩到身后）。烟花矢量吃完后平飞 10 tick，再 35° 起飞，循环。
4. **单开**：新增 `SpearAirRaidBehavior`（core 行为，优先级 237）与 `combat.spearAirRaid` 开关；
   通用空袭在 `isActive` 为真时整段让位，且不再识别长矛（`spear=false`）。关闭开关 = 长矛按
   普通近战武器走通用空袭的收翅猛击，不会退回旧的水平突袭。
5. **配置面板单开**：新增「战斗与自保 → 长矛核心」页。原先误挂在「落地缓冲」页的攻击模式冲锋、
   埋在「空袭数值」页中间的五条长矛参数全部迁入；空袭页只留「长矛空袭 → 前往」入口。
6. **平地转圈**：`SpearAttackBehavior` 每 tick 无条件 `lookAt(target)`，而 `OPENING` / `RETREAT`
   同时往背离目标的方向寻路；移动控制按身体朝向算速度 → 她一边看目标一边被要求走反方向，
   在原地打转。两段改为面朝本次移动落点（`faceToward`），并在 1 秒仍拉不开时直接掉头冲锋。
7. **牵引绳**：`isEngaged` 纳入独立链路；`MaidFlightRecall` 再补一道「有活目标在 50 格内」的
   兜底，覆盖行为 stop/start 那一拍状态表为空的窗口。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`（新增，502 行）：独立状态机
  （TAKEOFF / CLIMB / APPROACH / BOOST / RECOVER）、35° 爬升/俯冲、水平点火、起跳失败退避。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`：通用空袭在独立链路激活时
  让位；`spear` 恒 false（旧水平突袭退役）；`kickOffGround` / `DiveBoostRocket` 改为包内可见；
  `isEngaged` 纳入独立链路。
- `promaid_src_neo/com/maidsmart/combat/SpearAttackBehavior.java`：OPENING/RETREAT 面朝移动落点；
  1 秒拉不开就掉头冲锋。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightRecall.java`：有活目标交战时跳过 100 格牵引绳。
- `promaid_src_neo/com/maidsmart/combat/MaidCombatTacticsBehavior.java`：战术走位/举盾在独立链路
  激活时让位。
- `promaid_src_neo/com/maidsmart/ProMaidExtension.java`：注册 `SpearAirRaidBehavior`（237）。
- `promaid_src_neo/com/maidsmart/config/MaidSmartConfig.java`：新增 `combat.spearAirRaid`、
  `airRaid.spearClimbPitch`、`airRaid.spearDivePitch`。
- `promaid_src_neo/com/maidsmart/config/PromaidConfigScreen.java`：新增「长矛核心」分区页；
  迁移长矛行；空袭页留跳转。
- `promaid_src_neo/assets/maid_smart/lang/zh_cn.json` / `en_us.json`：三条新译文。
- `tools/verify/check_preset.py`：Neo DIY 关闭基线 112 → 113。
- `tools/arch/arch_modules.py` 与 `normify-promaid/`：新行为登记、行号同步、架构产物重新生成。
- `rounds/RELEASES.tsv`、发布文档与桌面文本：追加本轮说明。

## 验证

- Neo 编译：`EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`；`check_preset` Neo DIY 113 = 新基线；`check_arch` 未覆盖 181 = 基线；
  `check_comment_style` 0 处；`check_i18n_keys` 0 处。
- Neo jar：18,123,380 字节，SHA256
  `871e1931770076a7e0a788922ccf3c6f5d675db70c5889e18402643a8caf2f12`。
- 专服（测试服 mods 临时放 `spearcore-1.21.1-neo-3.1.0.jar`，取自本机 1.21.1 游戏 mods）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS。测试后已移除测试 jar。
- 已部署 1.21.1 实例，源包/目标包 SHA256 一致；桌面快照同步；Forge 源码树本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后**再做。
- 独立链路只在「近战空袭任务 + 主手长矛 + 鞘翅/武器/推进剂齐备 + 身上有烟花」时启动；
  没有烟花（只剩扇子/法术/自推鞘翅）时交回通用空袭，避免本链路只认烟花却硬占走位。
- 关闭 `combat.spearAirRaid` = 长矛按普通近战武器走通用空袭；攻击模式冲锋仍由
  `combat.spearCharge` 独立控制。
- 35° 是默认值，两条角度参数都可调；调大仍不会回到 62° 的背离起飞。
- 版本号仍为 1.3.3（同号重打），不升号。

## 验收

- 配置面板：进入「战斗与自保」，应能看到独立小类「长矛核心」；页内第一条是攻击模式冲锋，
  第二条是空袭独立链路，下面有新角度与五条旧空袭参数。「空袭数值」页里原长矛参数应只剩
  一个「长矛空袭 → 前往」入口。
- 空袭：给女仆鞘翅 + 长矛 + 烟花，切近战空袭。她用长矛时应**朝目标 35° 起飞**，爬到目标上方
  约 3 格后浅角俯冲；接近到水平线且离目标 8~20 格时补一记烟花，随后贯穿。日志关键词：
  「进入长矛空袭独立链路」「爬升结束」「水平贯穿点火」。不应再出现每 6.5 秒一次的
  背离起飞、也不应在 100 格内被牵引绳拽回。
- 平地攻击模式：把怪放到她 9 格内。观察她先举矛后撤时**面朝后撤方向**跑出去，不再原地转圈；
  1 秒内拉不开就直接掉头冲锋。
- 关闭「长矛核心·空袭独立链路」：空袭时她应按普通近战武器收翅猛击，不出现水平贯穿日志。
- 卸载 `spearcore`：独立链路不启动，所有新增判据恒 false，无报错。
