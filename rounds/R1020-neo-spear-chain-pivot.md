# R1020 1.21.1 长矛三修：先打后撤 / 取矛复制 / 空袭回原链路（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复 / 兼容补齐（1.21.1 专属；R1019 的独立编队贯穿链路按开发者要求撤掉，
  回到原通用空袭链路上做最小改动；同时修攻击模式“命中后不后撤”与手槽取矛复制。
  1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`rounds/R1017-neo-spear-air-raid.md`、`R1018-neo-spear-attack-mode.md`、
  `R1019-neo-spear-air-pierce.md`、`MaidFlightCombatBehavior.java`、`MaidFlightKit.java`、
  `SpearAttackBehavior.java`、`SpearCoreCompat.java`、`MaidSmartConfig.java`、
  `PromaidConfigScreen.java`、`GuideChaptersFlight.java`；以及 TLM `MaidMainContainer` /
  `MaidHandsInvWrapper`、NeoForge `EntityEquipmentInvWrapper`、spearcore `SpearItem` /
  `LivingEntitySpearMixin` 的 javap 字节码。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 取矛复制取证 | 确认 `extractItem` 整组取出返回槽内**原对象**、`startUsingItem` 的 `useItem` 指向同一对象、`setStackInSlot` 的 `ItemStack.matches` 早退会造成手槽/光标同引用 | ☑ |
| 1 | 取矛保护 | 新增 `MaidHandExtractGuardMixin`：真正取物前收矛、返回副本；同款互换时强制写槽；core 行为每 tick 对齐陈旧 useItem | ☑ |
| 2 | 攻击模式先打后撤 | 命中（掉血或 `lastHurtByMob` 上升沿）→ 按命中方向再冲 20 tick → 折返后撤 → 压回去再冲；全程不收矛 | ☑ |
| 3 | 空袭回原链路 | 删除 `SpearAirChain`；原链路的俯冲段对长矛改为幻翼式压平，水平时点一记烟花，矢量结束再起飞；全程保持蓄力 | ☑ |
| 4 | 配置与文案 | 五条长矛空袭配置语义改为点火/压平/矢量/缓冲/超时；攻击模式说明、面板、双语、手册同步 | ☑ |
| 5 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0`；架构/面板生成物同步 | ☑ |
| 6 | 专服回归 | 测试服挂载 `spearcore-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS，测试后移除测试 jar | ☑ |
| 7 | 部署 Neo | 只部署 1.21.1 实例；Forge 两个实例与 pack1201 一律不动 | ☑ |
| 8 | 开发者实机验收 | 见下方「验收」 | ☐ |
| 9 | 批准后镜像 Forge | 仅在开发者明确批准后执行；本轮不做 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话（本轮）：
  「1. 攻击模式下，女仆在触发了一次伤害后没有做到先打后撤。其实长矛的战术可以直接套用我们的既有的
  边打边退，链路：发现敌人→举矛进入冲锋状态→朝敌人方向冲锋→造成伤害后再按这个方向保持冲锋再走
  一秒→（套用边打边退的退）赶紧折返方向跑出范围。随后再次冲锋。女仆走后门，全程处于巅峰冲锋状态。
  2. 女仆手上的长矛取不下来，尝试拿下来的话会无限刷物品。
  3. 空袭长矛调整：起飞与其他完全一致→进入冲锋状态→（下落发生变化）采用类似幻翼的俯冲方式和移动
  逻辑，保证最终接近敌人时与地面水平→发现水平后补一记烟花（原本链路的俯冲时加速放这）→烟花矢量
  结束后再次起飞。情况有变，不再重写链路，长矛的链路在原通用链路上改。女仆在进入冲锋后依旧全程
  巅峰冲锋，威胁解除/模式切换后取消。」

## 评估

1. **取矛复制**：NeoForge `EntityEquipmentInvWrapper.extractItem` 在整组取出时把槽里的
   `ItemStack` **原对象**返回并把槽置空（不 copy）；原版 `LivingEntity.startUsingItem` 又把
   `useItem` 直接指到同一对象（javap 实证）。槽的 `setStackInSlot` 还有一句
   `ItemStack.matches(current, stack)` 提前 return：同款长矛互换时不真正写槽，容器菜单却把
   光标换成槽里那份的引用。于是“手槽 / 光标 / 背包”出现同一对象的多份引用，经
   `split/grow` 变成真实复制。修法是把三条口都堵住：取物前收矛、取物返回副本、同款互换强制写槽。
2. **攻击模式**：R1018 只等 `isCharging` 变 false（长矛自己 200~315 tick 收手）才后撤，
   期间没有伤害检测，所以“打中了也不撤”。本轮改为盯目标掉血 / `lastHurtByMob` 上升沿；
   命中后按命中时刻的速度方向再冲 1 秒，再套用既有 `LandRandomPos.getPosAway` 后撤段。
   全程 `startUsingItem`，后撤也不收矛；长矛到时限收手立刻续上。
3. **空袭**：R1019 的 `SpearAirChain` 是独立状态机，与开发者“不再重写链路”的要求冲突。
   本轮删除它，回到原通用空袭：起飞/爬升/拉开/再点火全沿用；只把“收翅俯冲→猛击”这一段
   对长矛替换为幻翼式压平——按高度差逐步压低机头，接近目标时与地面水平，点一记烟花，
   吃完矢量再平飞缓冲、走原 `WAIT_LAUNCH` / `tryLaunch` 起飞。伤害仍由 spearcore 结算。

## 改动文件

- `promaid_src_neo/com/maidsmart/mixin/MaidHandExtractGuardMixin.java`（新增）：`extractItem`
  取物前 `stopCharge`、返回 `copy()`；`setStackInSlot` 在 `matches` 早退时强制写主手槽。
- `promaid_src_neo/mixins.promaid.json`：登记新 mixin。
- `promaid_src_neo/com/maidsmart/task/MaidToolAutoEquipBehavior.java`：core 每 tick 调
  `SpearCoreCompat.guardStaleUse`，任何玩法下都清理陈旧的 `useItem` 引用。
- `promaid_src_neo/com/maidsmart/combat/SpearCoreCompat.java`：新增 `guardStaleUse`。
- `promaid_src_neo/com/maidsmart/combat/SpearAttackBehavior.java`：重写为
  APPROACH / CHARGING / AFTER_HIT / RETREAT 四段；伤害检测、命中后 1 秒同向冲锋、
  不收矛后撤、24 格再交战窗口。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`：删除 `SpearAirChain`
  接入；新增 `SPEAR_PASS` 状态与 `tickSpearPass`（平飞接近 → 压平 → 水平点火 → 矢量 →
  缓冲 → 原链路再起飞）；全程维持蓄力；跳过长矛链路的施法；落地/丢目标/切任务收矛。
- `promaid_src_neo/com/maidsmart/combat/SpearAirChain.java`：删除（jar 内旧 class 已清）。
- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatTask.java`：类注释改为“原链路 + 水平突袭”。
- `promaid_src_neo/com/maidsmart/config/MaidSmartConfig.java`：五条长矛空袭配置注释/语义更新
  （点火距离、开始压平距离、烟花矢量时长、烟花后平飞、单次突袭最长）；攻击模式说明更新。
- `promaid_src_neo/com/maidsmart/config/PromaidConfigScreen.java`：五条面板行标签/说明同步；
  攻击模式开关说明改为“命中后 1 秒 → 后撤 → 再冲”。
- `promaid_src_neo/assets/maid_smart/lang/{zh_cn,en_us}.json`：五条配置译文同步。
- `promaid_src_neo/com/maidsmart/guide/GuideChaptersFlight.java` 与
  `promaid_src_neo/assets/promaid/i18n/en_us.json`：手册长矛条目改为“原通用链路 + 幻翼式压平”。
- `tools/arch/arch_modules.py` 与 `normify-promaid/`：新 mixin 登记、架构产物重新生成。

## 验证

- Neo 编译：`EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`；`check_mixin_targets` 校验 138 个注入点；
  `check_arch` / `check_guide_i18n` / `check_i18n_keys` 全部 0。
- Neo jar：18,107,775 字节，SHA256
  `95466166d530b295ea625a4f4b0bf77955f38caa3476acc6deb1b15e6284b5fb`。
- 专服（测试服 mods 里临时放 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS
  （女仆大脑类与新手槽 mixin 正常加载）。测试后已把测试 jar 移出测试服。
- 已部署 1.21.1 实例，源包/目标包 SHA256 一致；未重打/未部署 Forge 侧 jar；Forge 源码树本轮零改动。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后**再做。
- 攻击模式的后撤方向取命中那一刻的实际水平速度；目标在 24 格外视为脱战，立即收矛。
- 空袭的压平高度取目标脚上方 0.35 格（长矛线沿眼睛高度水平通过目标碰撞箱）；地形抬升、
  前方挡墙、触地或超时都会放弃这次下扎、重新起飞，不会贴地穿墙。
- 空袭只消耗真实烟花 1 枚/次；没有烟花但有羽扇/法术/自推鞘翅时仍走原起飞链路，只是这一次
  水平突袭没有额外加速。
- 取矛保护只动 TLM 女仆主手槽（`MaidHandsInvWrapper` slot 0）；副手/护甲/其它模组的装备
  包装器不受影响。
- 未安装 `spearcore` 时 `SpearCoreCompat.isSpear` 恒 false，两条长矛链路都不介入。
- 版本号仍为 1.3.3（同号重打），不升号。

## 验收

- 攻击模式：给女仆长矛、切攻击模式、放一只会追人的怪。观察她举矛冲上去，**打中后**沿原方向
  再冲约 1 秒，然后折返跑开；跑出后还会自己压回去再冲。全程矛一直在举着（有长矛使用音效的
  续力），不会中途收矛。日志搜「长矛」可看到冲锋/后撤节奏。
- 取矛：打开女仆背包，反复把主手长矛拿走、放回、再拿走；物品数量应守恒，矛能正常取下，
  女仆手里不会残留“看不见的矛”；拿走后不会凭空多出一把。
- 空袭：给女仆鞘翅 + 长矛 + 烟花，切近战空袭。观察起飞与其他武器一致；接近目标时不再垂直
  扎下去，而是压平机身后**与地面水平**掠过，水平那一刻点一枚烟花，吃完矢量再起飞。进入冲锋
  后全程举矛，目标死亡/切任务才收矛。
- 把长矛换成重锤或普通剑：行为回到原有重锤猛击/普通俯冲链路。
- 卸载 `spearcore`：两条长矛链路都不介入，行为与 R1018 一致，无报错。
