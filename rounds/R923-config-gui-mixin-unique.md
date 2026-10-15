# 实测九百二十三 消除女仆配置界面 Mixin 的静默覆写冲突（"警告而非报错"的根治）

> 跑道：**B 功能抢修**——开发者看了"AI 做模组总有问题"的视频后指出：「什么叫 mixin 注入失败强制静默，什么叫 forge 事件注册失败直接丢弃？…埋下了不少隐患。最近运行，我们的模组会出现一些警告，而不是报错。我需要细究这些警告…有的话，必须要进行根治。」
> 开发者指令（原话）：「我需要细究这些警告，看看有没有出现这样的蠢问题。有的话，必须要进行根治。」
> 上一轮：`rounds/R922-work-clamp-home-only.md`。

## 排班

- **本轮唯一主项**：**审计运行期警告**（三个实机实例的 `latest.log` / `debug.log` / `promaid.log`），把"我们的模组自己发出的 WARN/ERROR"逐条查清；对其中任何"静默失败"类问题**从根上改掉**。功能面一律不动（挖矿/伐木/驾驶/战斗/建筑/数值不碰）。
- 步骤与完成判据：
  1. **抓警告**：三实例（`1.21.1-NeoForge_21.1.250` / `1.20.1-Forge_47.4.10` / `龙之冒险：新征程v2.4a`）日志里筛 `promaid|maidsmart|mixin` 的 WARN/ERROR，并**归类**哪些是我们发的、哪些是别的模组发的。
  2. **对两个点名反模式**：①"mixin 注入失败强制静默"——查 `promaid_src/com/maidsmart/mixin/**` 的 `require` 设置与运行期 `[mixin/]` 警告；②"forge 事件注册失败直接丢弃"——查 `ProMaidMod` / `ProMaidExtension` 的 `register` / `addListener` 路径有没有被 `try/catch` 静默吞掉。判据：能逐条给出"有/没有"的证据。
  3. **根治**：凡是"失败被静默丢掉"的，改成"失败留痕 / 不再丢"。判据：改完后该警告消失，且行为回到设计口径。
  4. **改 Forge 树** → 5. **镜像 Neo 树**。判据：两树 `javac` `EXITCODE=0`。
  6. **护栏 → 打 jar → 部署 4 实例**。判据：全护栏 `FAIL 0`；4 个目标全部 `match=True`。
- 谁验收：开发者（本轮只部署、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

- 开发者两条诉求：(a) 事实性提问——"原版 TLM 开罗盘之后，跟随状态的女仆也会被牵制住吗？"（答案见 `## 附`）；(b) 代码质量审计——查"警告而非报错"里有没有"静默失败"的蠢问题，有就根治。

## 审计结论（三实例日志）

**属于我们模组的 WARN/ERROR，只有一条（且是同一个）**：

```
[Render thread/WARN] [mixin/]: Method overwrite conflict for promaid$value in mixins.promaid.json:MaidConfigGoetyMixin from mod promaid, previously written by com.maidsmart.mixin.MaidConfigFreeFlightMixin. Skipping method.
[Render thread/WARN] [mixin/]: Method overwrite conflict for promaid$addRenderable in mixins.promaid.json:MaidConfigGoetyMixin from mod promaid, previously written by com.maidsmart.mixin.MaidConfigFreeFlightMixin. Skipping method.
```

- 在**三个实例都出现**：`1.21.1-NeoForge_21.1.250`（1.21.1）、`1.20.1-Forge_47.4.10`（1.20.1）、`龙之冒险：新征程v2.4a`（1.20.1）。
- 其余 WARN/ERROR 全部**不是我们发的**：`[mixin/]` 的 `Reference map 'xxx.refmap.json' could not be read` / `Error loading class: … ClassNotFoundException` / `@Mixin target … was not found`（geckolib / iris / curios / imblocker / relics 等其它模组）、以及两个 `NullPointerException` 来自 `jeresources`（JER 读村民交易时 `VillagerTrades$TreasureMapForEmeralds` / Repurposed Structures 的同名类里 `entity` 为 null）——与本模组无关。
- 本模组**没有** `promaid.refmap.json` 警告（我们没有 refmap，符合预期）。
- `Diag.swallow`（我们所有 `catch(… ignored)` 的统一留痕出口）在三个实例里**一条 `已吞掉异常` WARN 都没写出**——说明没有被静默吞掉的真实异常在本次会话里触发过。

### 对两个点名反模式

1. **mixin 注入失败强制静默？** 全仓 `require = 0 / -1` 只在 5 处（`ExplosionWindGuardMixin` / `MaidFlightDiveTiltMixin` / `MaidGeckoSpinDedupMixin` / `MaidMeleeRiptideMixin` / `PuppetModeLlmGuardMixin`），**每一处都写了为什么**（都是"撤销别人行为 / 可选兼容 / 版本漂移时只让这一条失效"的**刻意**防御，不是"失败就静默丢弃"）。**结论：没有这种蠢问题**；但发现了**另一种**静默失败——见下。
2. **forge 事件注册失败直接丢弃？** `ProMaidMod` / `ProMaidExtension` 里的 `register()` / `addListener()` / `MinecraftForge.EVENT_BUS.register(...)` **全部裸调用、没有一处包 `try/catch`**——注册失败会**当场抛异常、崩得满屏都是**，不会静默。唯一的 `try/catch` 在 `registerAITool` 里包 `Class.forName("com.maidsmart.dialogue.WorldProbe")` 预热，且 `catch` 里 **`log.warn(...)` 打出真实原因**，不是丢弃。**结论：没有这种蠢问题。**

## 定位：这一条静默失败到底是什么

1. **三个 Mixin 同挂一个目标类** `MaidConfigContainerGui`：`MaidConfigMemoryMixin`（AI 记忆行）、`MaidConfigFreeFlightMixin`（创造飞行行）、`MaidConfigGoetyMixin`（飞行聚晶行）。
2. 其中后两支**各自**声明了**同名同签名**的 `private static` 助手：`promaid$value(String)` 与 `promaid$addRenderable(Screen, GuiEventListener)`。它们是**普通私有方法**（非 `@Unique`），Mixin 会**按名并入目标类**。
3. 三支都在 `mixins.promaid.json` 的 `client` 列表里。先被应用的 `MaidConfigFreeFlightMixin` 先把 `promaid$value` / `promaid$addRenderable` 写进目标类；轮到 `MaidConfigGoetyMixin` 时，Mixin 发现同名方法已在 → **判为"重复覆写"，整条跳过（Skipping method）**。这就是那两行 WARN 的来源。
4. **后果不只是刷警告**：被跳过的是**飞行聚晶**那一份助手，而它注入体内的 `invokestatic promaid$value` / `promaid$addRenderable` 是**按名解析**的——落到的是**创造飞行**那一份上。于是「飞行聚晶」这一行的开关文本读的是**创造飞行**的状态（`MaidFreeFlightFlags`），而不是它自己的（`MaidGoetyAuto`）。而且这是**顺序偶然**：哪天有人调整 Mixin 列表顺序、或删掉创造飞行那一支，飞行聚晶行就会**无声失效**——正是开发者担心的那种"埋隐患"。

## 改法（两树镜像）

给两支 Mixin 的这两个私有助手各加 `@Unique`：

```java
@Unique
private static String promaid$value(String uid) { … }

@Unique
private static void promaid$addRenderable(Screen screen, GuiEventListener widget) { … }
```

- **为什么 `@Unique` 是对症的正解**：`@Unique` 就是 Mixin 给"这是我私有的、别和目标/别的 Mixin 抢名字"的标记。标上后 Mixin 会按**各自 Mixin 的命名空间**给它重命名（例如 `maidConfigGoetyMixin$promaid$value`），两支的助手**各自都在**、各注入体各调各的，`Skipping method` 不复现。
- **无行为变化**：助手逻辑一字未改，只是从"被丢/串味"变成"各自的"。行文本回到正确来源（飞行聚晶行读 `MaidGoetyAuto`）。
- 顺带：`MaidConfigMemoryMixin` 的同类助手叫 `addRenderable`（不带 `promaid$` 前缀），与这两支**不同名**，本来就没冲突——不动它（避免无关改动）。

## 触及文件

- `promaid_src/com/maidsmart/mixin/MaidConfigFreeFlightMixin.java`（`@Unique` × 2 + import）
- `promaid_src/com/maidsmart/mixin/MaidConfigGoetyMixin.java`（`@Unique` × 2 + import）
- `promaid_src_neo/com/maidsmart/mixin/MaidConfigFreeFlightMixin.java`（同上，镜像）
- `promaid_src_neo/com/maidsmart/mixin/MaidConfigGoetyMixin.java`（同上，镜像）
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行 sha256 + 第六十二次同号重打）
- `rounds/R923-config-gui-mixin-unique.md`（本文件）

## 验证

- **两树编译**：`gen_compile.py` + `compile_promaid.bat` → `EXITCODE=0`；`gen_compile_neo.py` + `compile_neo.bat` → `EXITCODE=0`。
- **全护栏**：`check_comment_style` **PASS**（931 文件 / 0 处）；`check_arch` / `check_catch_swallow` / `check_comment_dup` / `check_dev_voice` / `check_i18n_keys` / `check_panel_coverage` / `check_preset` / `check_reflection` **FAIL 0 / WARN 0**；`check_workflow` **FAIL 0 / WARN 3**（历史遗留：PUBLISH_GUIDE 版本 1.3.1、1.2.4 归档哈希、13 个更早 jar 未登记——与本轮无关）。
- **两个 jar**：`verify_jar_classes` **OK**（mixin 包登记 OK、注入签名 OK；`out` ↔ `jar` class 双向一致）。
- **字节码复查**：`javap -p -v -cp patched/promaid-1.3.2-forge-1.20.1.jar com.maidsmart.mixin.MaidConfigGoetyMixin` → 两个助手都带 `org.spongepowered.asm.mixin.Unique`（常量池 `#172 = Utf8 Lorg/spongepowered/asm/mixin/Unique;`）。
- **未跑实机回归**（先停在这，等开发者指挥）。

### 部署

- 打 jar：`build_promaid.py` → `patched/promaid-1.3.2-forge-1.20.1.jar`（**17582625** 字节，sha256 `AF4B765A…A3ED22`）；`build_promaid_neo.py` → `patched/promaid-1.3.2-neoforge-1.21.1.jar`（**17619012** 字节，sha256 `75F9251B…D1F480`）。
- 装机：`deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201）；旧包已备份在 `patched/backup_old/`。
- 留痕：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行的 sha256 列更新为新字节，并追加「第六十二次同号重打」；R922 旧字节 `70EBF870…D17D8E`（forge）/ `FBFAC765…0BD6FB`（neo）作废。
- 到此为止：**未跑回归、未验收、未 commit、未 push**。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
  - 复现/验证步骤（**下一次启动游戏即验**）：进游戏打开任意女仆的**配置界面**（右键女仆）→ 看 `logs/latest.log` / `debug.log`：**不应再出现** `Method overwrite conflict for promaid$value` / `promaid$addRenderable` 这两行 `[mixin/]` 警告。
  - 行为验证：面板左侧那一列窄按钮里，「飞行聚晶」这一行的值文本应随**飞行聚晶**自身的开/关变化（点一下它、等 S2C 回来，文本即时纠正），不再串成「创造飞行」的状态。
  - 判定：日志无该两行 + 飞行聚晶行文本跟随自身状态 = 合格。若仍出现，请贴 `logs/latest.log` 里 `[mixin/]` 那两行 + `javap` 结果。

## 边界（没动什么）

- **没动**：三支 Mixin 的注入点、`initAdditionWidgets` / `renderAddition` 的口径、`MaidConfigRowLayout` 的布局算法、任何功能行为；`require = 0` 的那 5 处**刻意**防御保持原样（它们是"可选/撤销类"注入，改成 `require=1` 反而会在版本漂移时硬崩）。
- **不新增开关、不改默认值**；版本号仍 **1.3.2**（`WORKFLOW.md` §5 "验收前不升号"）。
- 临时脚本（`tools/_ledger923.py`）**已删**；仓库根目录既有的 `_*` 文件与 `_tmp_pub` 等未动。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第六十二次同号重打。
- `DEBT.md`：本轮不新增条目——本行消除的是一处**静默失败**（同名私有助手被 Mixin 丢弃），属于"把已有的沉默改成正确"，不引入新的未验证行为。

## 附：原版 TLM 开罗盘会牵制跟随状态的女仆吗？

**不会。** 反编译 TLM 1.5.x 逐条核对（`analysis/cfr.jar`）：

- `ItemKappaCompass.interactLivingEntity`（右键女仆）写得清清楚楚：写 `setDimension` / `setWorkPos` / `setIdlePos` / `setSleepPos` / `setConfigured(true)`，最后 `restrictTo(maid)` ——**全文没有任何 `setHomeModeEnable` 调用**。潜行右键（清除模式）走 `SchedulePos.clear(maid)`，同样只清锚点。
- `SchedulePos.restrictTo(maid)` 第一行就是 `if (!maid.isHomeModeEnable()) return;` ——home 没开时**直接返回**，圈根本不落地。
- `EntityMaid.m_21536_()`（hasRestriction）= `isHomeModeEnable()`；`m_21444_(pos)`（isWithinRestriction）在 home 关时 `return true`。
- `SchedulePos.tick`（每 40 拍）：`restrictTo`（no-op）之后 `if (maid.m_21533_()) return;`；而 `m_21533_()` = `m_21444_(自己脚下)` = home 关时 true → **不会**把她传回限制中心。
- 跟随任务 `MaidFollowOwnerTask.checkExtraStartConditions` = `!maid.isHomeModeEnable() && maid.canBrainMoving()`；`MaidMoveToBlockTask` 里 `if (maid.isHomeModeEnable())` 才用圈心选点；`MaidUpdateActivityFromSchedule` 只在 home 开时调 `restrictTo`。

**一句话**：罗盘只负责"写锚点"，**不负责"开 home"**；圈只在 home 模式（守家）下生效。会开 home 的是「这块归你管」工具与排班。所以**原版 TLM 里，开罗盘并不会牵制跟随状态的女仆**——这也正是 R922 把 `WorkAreaClamp.allowsWork` 改成"只认 home"的依据。
