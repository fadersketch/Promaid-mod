# 实测一千〇一十六 蛋糕冲突自动关闭 + 拆探针 + 1.3.3 发布准备（1.3.3 第 146 次同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug / 冲突逃生 + 收尾（蛋糕冲突自动关闭是既有 `misc.cakeEdible` 的冲突逃生，
  不新增配置项或能力）+ 发布准备。一句话判定依据：开发者明确要求“其他模组改了蛋糕时本模组蛋糕开关
  自动关闭”，其余是已验收修复的清理与交付。
- **开工前读过**：`rounds/R1012-equip-probe.md`（第五轮验收已通过，可拆探针）、`ARCHITECTURE-SWITCHES.md`
  的「系统与杂项 → 交互与杂项」（已有 `misc.cakeEdible`，不新增开关）、`rounds/RELEASES.tsv`（第 145 次）、
  `WORKFLOW.md` 发布流程、桌面 `Promaid文本/写作规则.md`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位蛋糕链路 | Forge `MaidCakeEdibleMixin` 两方法 + Neo `ModifyDefaultComponentsEvent` + 两树 `MaidCakeEatHandler.isCake` 全部找到 | ☑ |
| 1 | 冲突探测设计 | 探测时本模组让路，读到的是原版/其他模组的真实结果；不误伤普通物品 mixin | ☑ |
| 2 | 两树实现 + 编译 | 探测、自动置 false、日志；`EXITCODE=0` | ☑ |
| 3 | 拆探针 + 基线 | 五个诊断类与四处 mixin 登记全删；`check_arch` 基线回 181 | ☑ |
| 4 | 14 护栏 | `FAIL 0 / WARN 0` | ☑ |
| 5 | 双 jar + 回归 | Forge/Neo 构建成功；专服三件 PASS | ☑ |
| 6 | 冲突路径实测 | 临时测试模组在 promaid 之前给蛋糕挂不同 FOOD → 本模组自动关闭并留日志 | ☑ |
| 7 | 写发布说明 | 桌面版本文件 + 仓库 `RELEASE_NOTES_v1.3.3.md` / `release_body*.md` | ☑ |
| 8 | 部署 4 实例 | `match=True` ×4（R1014 的 147 次重打已覆盖本项） | ☑ |
| 9 | 开发者实机验收 | 正常蛋糕可用；装了改蛋糕的模组时自动关闭 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法：①正常启动，吃蛋糕 / 喂蛋糕功能照旧；②若装了其他会改蛋糕的
  模组，启动后看配置面板「蛋糕可食用」应为关，`promaid.log` 有一行「检测到其他模组已修改蛋糕」；
  ③装备消失复验：传送后逐件取放两轮，不再消失。

## 需求 / 来源

- 开发者原话（本轮新增需求）：「如果有其他的模组修改了蛋糕的mixin,那么本模组蛋糕的mixin相关开关自动关闭。」
- 同轮指令：装备消失已验收通过（“修好了”），拆掉诊断针；准备 V1.3.3 发布说明（临时追加更新：
  飞行跟随刷物品、安装装备问题、日程表内女仆丢失，另加体验优化），完成后部署到开发者游戏里验收。

## 评估

- **Forge（1.20.1）**：蛋糕可食用由 `MaidCakeEdibleMixin` 注入 `Item.m_41472_`（isEdible）与
  `Item.m_41473_`（getFoodProperties）。探测时让 `isCake()` 暂时返回 false（`PROBING`），再直接调用
  `Items.f_42502_.m_41472_()/m_41473_()`：如果返回 true / 非空，说明原版或其他模组的注入已经让蛋糕可食用，
  本模组自动关闭。只看行为结果，不按“谁 mixin 了 Item”一刀切，避免被无关的 Item mixin 误伤。
- **NeoForge（1.21.1）**：没有 cake mixin，走 `ModifyDefaultComponentsEvent`。监听器改为 `LOWEST`，
  轮到本模组时先读 `Items.CAKE.components().get(DataComponents.FOOD)`：已有 FOOD = 其他模组改过，
  本模组不覆盖并自动关闭；没有才挂自己的 FOOD。全部加载结束后（`ServerStartedEvent`）复核最终组件，
  仍不是本模组那份就自动关闭（覆盖“别的模组后写”）。
- **自动关闭口径**：`MISC_CAKE_EDIBLE.set(false)`（面板同步显示为关）+ 静态 `AUTO_DISABLED` 双保险；
  日志写明“检测到其他模组已修改蛋糕”。需要恢复时在配置面板手动打开。
- `ARCHITECTURE-SWITCHES.md` 已有等价开关（`misc.cakeEdible`），本轮不新增配置项。

## 改法

- Forge `MaidCakeEatHandler`：新增 `AUTO_DISABLED` / `PROBING` / `isEnabled()` / `isProbing()` /
  `detectExternalCakeModification()` / `disableBecauseConflict()`；`isCake()` 改用 `isEnabled()`。
  `ProMaidMod` 在 MOD 总线监听 `FMLCommonSetupEvent`，`enqueueWork` 后执行探测。
- Forge `MaidCakeEdibleMixin.isCake()`：探测期间与自动关闭后一律返回 false（mixin 完全透明）。
- Neo `MaidCakeEatHandler`：新增同样的总闸与冲突日志；`onModifyDefaultComponents` 先查蛋糕已有 FOOD，
  有则让出并关闭；`verifyAfterLoad()` 改为在 `ProMaidExtension.onServerStarted` 复核最终组件。
  `ProMaidMod` 的监听器改为 `EventPriority.LOWEST`。
- 拆除五个诊断类（`EquipProbe`、`ContainerClickProbeMixin`、`EquipClearTraceMixin`、
  `CarriedClearTraceMixin`、`ItemStackZeroTraceMixin`）及两树 mixin 登记；`ProMaidExtension` 去掉
  tick 调用、`openMenuState` 与 `[装备离开]` 的界面/光标后缀。
- `check_arch.py` 的 `BASELINE_UNCOVERED['promaid_src_neo']` 185 → 181。
- 保留 `EquipmentPacketCopyMixin`（装备消失的真修复，长期代码）。
- 发布说明：桌面 `v1.3.3 临时追加更新：三项紧急修复与体验优化.md`、`Promaid_v1.3.3_发布声明.md`；
  仓库 `RELEASE_NOTES_v1.3.3.md`、`release_body.md`、`release_body_neo.md`。

## 触及文件

- `promaid_src/com/maidsmart/task/MaidCakeEatHandler.java`
- `promaid_src/com/maidsmart/mixin/MaidCakeEdibleMixin.java`
- `promaid_src/com/maidsmart/ProMaidMod.java`
- `promaid_src/com/maidsmart/ProMaidExtension.java`
- `promaid_src_neo/com/maidsmart/task/MaidCakeEatHandler.java`
- `promaid_src_neo/com/maidsmart/ProMaidMod.java`
- `promaid_src_neo/com/maidsmart/ProMaidExtension.java`
- `promaid_src/mixins.promaid.json` / `promaid_src_neo/mixins.promaid.json`（删诊断针登记）
- 删除：两树 `diag/EquipProbe.java`、`mixin/EquipClearTraceMixin.java`、`mixin/CarriedClearTraceMixin.java`、
  `mixin/ItemStackZeroTraceMixin.java`；Forge 另删 `mixin/ContainerClickProbeMixin.java`
- `tools/verify/check_arch.py`（基线回 181）
- 发布说明 5 份（桌面 2 + 仓库 3）

## 验证

- 两树编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0`；`check_arch` Neo 未覆盖 181 = 基线 181；
  `check_mixin_targets` Forge 269 / Neo 134，均高于下限。
- 双 jar：Forge `18,011,628` 字节 / sha256 `af8b6d0b…b745a8`；Neo `18,059,021` 字节 /
  sha256 `3c2cd3e1…479479`。
- 专服回归：`test_server.py 1201` PASS、`test_server.py neoforge1211` PASS、
  `test_maid_load.py neoforge1211` PASS。
- 冲突路径实测：临时测试模组（`cakeconflicttest`，在 HIGHEST 优先级给蛋糕挂营养 3 的 FOOD）与专服同跑，
  日志出现「检测到其他模组已修改蛋糕（已有 FOOD 组件：营养 3 / 饱和 1.8000001），本模组的蛋糕功能已自动关闭
  （可在配置面板手动恢复）」，配置写回 `cakeEdible=false`；移除测试模组并恢复 true 后，日志回到
  「已给 minecraft:cake 挂上 FOOD 组件」，功能正常。测试模组已从测试服删除。
- **反证**：冲突测试模组在场 → 自动关闭；移除后 → 正常挂载。Forge 正常专服探测日志为
  「未检测到其他模组修改蛋糕，按配置开启」。
- 未做：开发者实机验收（部署后执行）。

## 验收

- 状态：`未验收`。部署到 4 个实例后由开发者实测。
- 验收通过前不写平台发布、不提交远端。

## 边界

- 蛋糕探测只针对“蛋糕是否已经可食用 / 是否已有 FOOD 组件”这一事实；其他模组仅对普通物品做无差别修改
  不会触发关闭。
- 自动关闭会同步把配置值置 false（可能随配置保存持久化）；需要恢复时在面板手动打开。
- 本轮不动 `MAID_CAKE_FAVOR` 相关的好感数值、投喂文案与语音；也不改 `EquipmentPacketCopyMixin` 的复制逻辑。
- 诊断针拆除后不再输出 `[装备探针]` / `[点击探针]` / `[清槽追踪]` / `[光标清理]` / `[光标对象]` /
  `[清零追踪]` 日志；`[装备离开]` / `[装备落定]` 保留（常规运行日志）。

## 台账

- 清掉：装备消失诊断针的临时架构基线（185 → 181）。
- 新增：无。
- 配额：本轮为开发者点名的追加修复 + 收尾，按 §8 不适用。
