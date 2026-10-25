# R1046 1.21.1 暂时停用长矛空袭兼容 + 1.20.1 镜像盘点（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 回退（1.21.1 NeoForge 专属；源码保留，按开发者要求“暂时删除”）。
- **开工前读过**：`rounds/R1017`–`R1045`、两树 `com/maidsmart` 文件清单与配置键、
  `ProMaidExtension` 的 brain 注册、`MaidFlightCombatBehavior` 的长矛分支。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 停独立链路 | `ProMaidExtension` 不再注册 `SpearAirRaidBehavior`（237）；群怪不再被它接管 | ☑ |
| 1 | 停通用链路长矛分支 | `spearArmed` 恒 false；不再记录长矛俯冲速度；`hitOne` 不再补长矛增伤 | ☑ |
| 2 | 保留可恢复性 | 源码、配置项与面板行保留；恢复时还原注册行与三处判断即可 | ☑ |
| 3 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0` | ☑ |
| 4 | 专服回归 | 临时挂载 spearcore：`test_server` / `test_maid_load` 均 PASS，测试后移除 | ☑ |
| 5 | 部署 | 只部署 1.21.1 实例；Forge 树与两个 Forge 实例不动 | ☑ |
| 6 | 镜像盘点 | 对照两树类清单、配置键与 R1017–R1045 的跑道记录，给出 1.20.1 差距清单 | ☑ |

## 需求 / 来源

- 开发者原话：「暂时删除我们做的对长矛空袭的兼容，然后确认一下其他的功能是否都已镜像到了 1.20.1 版本。
  然后回答我兼容了 1.20.1 的哪个重锤」
- 本轮只做“停用 + 盘点”，不删除源码、不改 Forge 树。

## 停用范围

- **停用**：
  - 独立长矛空袭链路 `SpearAirRaidBehavior` 的 brain 注册（`Pair.of(237, ...)` 已注释）；
  - 通用空袭里的长矛落地补判、路径补判入口（`spearArmed` 恒 false）；
  - 通用空袭里的长矛俯冲速度采样与 `hitOne` 速度增伤。
- **保留**：
  - 地面攻击模式的长矛兼容 `SpearAttackBehavior` + `SpearCoreCompat`（主手长矛蓄力/冲锋/取消平A）；
  - `SpearAirRaidBehavior.java`、`MaidVirtualFirework.java` 等源码与配置项（面板行仍在，恢复后即生效）；
  - 长矛姿态/挥臂 mixin 中的通用部分；其中 `SpearAirRaidBehavior.isActive` 因未注册恒为 false。
- **行为结果**：群怪与单怪都回到通用空袭链路；长矛不再触发独立俯冲/贯穿/速度增伤，按普通近战武器结算
  （地面长矛攻击模式仍照旧）。

## 1.20.1 镜像盘点

### 已镜像 / 已在 Forge 落地

- R1015：**1.20.1 重锤移植**——对应「试炼密室」回移植模组的 `trials:mace`（配合 `trials:wind_charge`），
  地面起跳猛击 + 战术面板/4 条配置项。
- R1016：**近战空袭的重锤链路**——同一套 `trials:mace` 在空袭收翅俯冲时的下落加成、装备位、空中联动。
- R1038：水中视线/净空判定（两树同改）。
- R1000–R1014 的常规修复：两树镜像或按加载器/映射差异分别落地。

### 未镜像（1.21.1 专属，等待批准或按设计不做）

- **长矛核心整套兼容（R1017–R1045）**：包括独立空袭链路、地面长矛攻击模式、路径/落地补判、
  速度增伤、100 格群怪分流与 1.21.1 专属调参。所有轮次均记录「1.20.1 等开发者验收批准后再镜像」。
- **R1042 的隐形烟花/鞘翅后门**：`MaidVirtualFirework`、壮丽/混沌鞘翅不消耗烟花的普通鞘翅物理、
  隐藏烟花粒子、以及同轮的 100 格群怪判定，均在 Neo 树，Forge 树没有对应实现。
- **`MaidShellHoming`（卓越前线炮弹寻的）**：1.20.1 侧已主动撤掉，Forge `ProMaidExtension` 留有说明。
- **`freeFlightGravity` 配置**：Neo 独有（Forge 无同名键）。
- **`MaidGoetyProbeCommand`**：Goety 兼容的无头诊断命令，属于开发工具，未在 Forge 落地。
- **类清单差异**：Neo 独有 `SpearCoreCompat` / `SpearAttackBehavior` / `SpearAirRaidBehavior` /
  `MaidVirtualFirework` / `MaidShellHoming` / `PotionCompat` / `MaidGoetyProbeCommand` 及 5 个长矛 mixin；
  Forge 独有 `CompressionBoxClickMixin` / `EntityTickPostMixin` / `MaidCakeEdibleMixin`（加载器/版本适配）。
  其中 `PotionCompat` 是 1.21.1 数据组件 API 适配，不代表功能缺失。
- **配置键差异**：Neo 多 9 个长矛键（`spearAirRaid`、`spearCharge`、`spearBoostInterval`、
  `spearClimbPitch`、`spearDivePitch`、`spearGroupRadius`、`spearMaxTicks`、`spearPassRange`、
  `spearRecoverTicks`）与 `freeFlightGravity`；Forge 多 `meleeReach`。

### 结论

**不是全部镜像。** 除长矛核心兼容与 R1042 的隐形烟花/鞘翅后门等明确标注为 1.21.1 专属的轮次外，
其余常规功能基本已在两树对齐；差异主要来自版本 API/加载器适配，而不是漏迁。

## 1.20.1 的“重锤”是哪一个

- 不是原版重锤（1.20.1 没有原版 Mace）。
- 兼容的是「试炼密室回移植」（Trials backport）模组注册的 **`trials:mace`**，
  风弹是 **`trials:wind_charge`**。R1015 移植地面起跳猛击，R1016 移植近战空袭里的下落加成。
- 识别方式按注册名 `trials:mace`，不硬依赖该模组的类；未安装时行为不会启动。

## 改动文件

- `promaid_src_neo/com/maidsmart/ProMaidExtension.java`：注释掉 237 号 `SpearAirRaidBehavior` 注册。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`（3732 行）：
  `spearArmed` 恒 false；移除长矛速度采样调用；`hitOne` 移除长矛增伤块。
- `rounds/RELEASES.tsv`、发布说明：追加本轮停用说明。

## 验证

- Neo 编译：`EXITCODE=0`。
- 全部护栏：`FAIL 0 / WARN 0`；`check_mixin_targets` 校验 277 个注入点。
- 专服（临时挂载 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS；测试后已移除。
- Neo jar：18,150,463 字节，SHA256
  `73519385C818A59F573662581D2B70EB33F20B0D16B9E15893624FFED43221B8`。
- 已部署 1.21.1 实例，源包/目标包字节数一致（18,150,463）。Forge 源码树与两个 Forge 实例本轮零改动。

## 验收

1. 群怪时不再出现「接管控空」「空袭·长矛」等独立链路日志；女仆按通用空袭链路落地/近战。
2. 地面攻击任务持长矛时，`SpearAttackBehavior` 的蓄力/冲锋仍照旧。
3. 面板里「长矛核心·空袭独立链路」开关仍在，但本轮构建不会产生空袭效果（恢复源码后生效）。

## 恢复方式

1. 取消 `ProMaidExtension` 中 `Pair.of(237, new SpearAirRaidBehavior())` 的注释；
2. `MaidFlightCombatBehavior.spearArmed` 换回「主手长矛 + `COMBAT_SPEAR_AIR_RAID`」判断；
3. 恢复 `rememberSpearSpeed` 调用与 `hitOne` 的长矛增伤块（见 R1042/R1044 文档）。

## 边界

- 仅 1.21.1 NeoForge；Forge 树本来就没有长矛空袭链路，本轮不动。
- 地面长矛攻击模式保留；停用的只是“空袭”这一条。
- 配置项与面板行保留，但 `combat.spearAirRaid` 在本轮构建里不会产生空袭效果。
- 版本号仍为 1.3.3（同号重打）。
