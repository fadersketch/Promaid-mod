# R1030 1.21.1 描边硬绑定 / 二次点火保持滑翔 / 手臂动作定位与拦截（1.3.3 同号重打）

> **后续：R1031 已回退本轮的"二次点火抬高度"（命中率下降）；描边硬绑定与手臂拦截保留。**

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复（1.21.1 专属；1.20.1 等开发者验收批准后再镜像）。
- **开工前读过**：`PlacedMarkNetworking`、`PlacedMarkClient`、`PlacedBlockTracker`、
  `SpearAirRaidBehavior.tickRelaunch`、`MaidFlightCombatBehavior.tryLaunchMelee`、
  TLM 的 `MaidBaseAnimation$10`（`isUsingItem` + 主手 → 手臂抬到 84°）、
  `MaidAttackStrafingTask`（`start()` 调 `setSwingingArms(true)`）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 描边不同步定位 | 描边表只由 noteAdd/noteRemove 驱动；方块被炸/替换/其他链路移除时没人撤框 | ☑ |
| 1 | 客户端硬绑定 | 渲染前检查该格是否空气；空气立即从表里删除并跳过绘制（仅视野内、区块已加载） | ☑ |
| 2 | 服务端对账 | 每 10 tick 核对描边格 = 登记方块 id；不符立即 untrack + 撤框；缺失描边反向补齐 | ☑ |
| 3 | 二次点火落地定位 | `tickRelaunch` 只 levelOut 等待，低空时在 40 tick 内掉到地面；RELAUNCH 不在容错相位 | ☑ |
| 4 | 空中续滑 | 低于目标上方 4 格改为抬头 21° 换高度 + 贴地托举；RELAUNCH 纳入擦地救起相位 | ☑ |
| 5 | 手臂动作再定位 | 截图确认：手臂是 `isUsingItem` 的使用姿势 + TLM 的 `DATA_ARM_RISE` 举臂，均非 swing | ☑ |
| 6 | 源头拦截 | 新增 `MaidArmRiseMixin` 拒绝 true；新增 `MaidSpearUsePoseMixin` 客户端把长矛使用姿势报 false | ☑ |
| 7 | 诊断日志 | swing 一参/二参、setSwingingArms(true) 都记录调用来源与状态（每 20/40 tick 限频） | ☑ |
| 8 | 编译与护栏 | Neo 编译 `EXITCODE=0`；14 护栏 `FAIL 0 / WARN 0`；注入点 275→277 | ☑ |
| 9 | 专服回归 | 测试服临时放入 `spearcore-1.21.1-neo-3.1.0`：`test_server` 与 `test_maid_load` 均 PASS，测试后移除 | ☑ |
| 10 | 部署与验收 | 只部署 1.21.1 实例；Forge 源码树与两个 Forge 实例一律不动 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话：
  「1.黄色渲染与放置方块仍不同步，应死绑，如果这个方块消失，则黄色也立即消失，必须是在代码上的死绑
  2.女仆在用完长矛空袭二次起飞时仍应是滑翔状态，而不是先落地。
  3.你还是没拦住动作，你最好做一个监听和日志来真正定位。」

## 评估

1. **描边**：描边表是纯消息驱动的——`PlacedBlockTracker.track/untrackAny` 才发增删。
   方块被爆炸、活塞、玩家或其他模组移除时不会经过这两个入口，框就留在原地。
   本轮做两层硬绑定：
   - 客户端渲染前对该格做 `getBlockState(pos).isAir()` 检查，空气立即从 `POS` 删除、当帧不画；
     只核对视野内（96 格）且区块已加载的格，避免把未加载区块误删。
   - 服务端每 10 tick 对账：`trackedBlockId` 必须等于该格当前方块的注册名且非空气；
     不符就 `untrackAny` + 撤框；反过来，登记过但没有描边的格补一帧。
2. **二次点火**：`tickRelaunch` 旧版只 `levelOut` 等烟花冷却，低空进场时 40 tick 内必掉地。
   现在低于目标上方 4 格就抬头 21°（把速度换成高度）并做贴地托举；RELAUNCH 也纳入
   `isSkimPhase`，擦地会被救起而不是退出链路。二次点火保持滑翔在空中完成。
3. **手臂动作**：截图与反编译确认有两个非 swing 来源：
   - TLM `MaidAttackStrafingTask.start()` → `setSwingingArms(true)` → `DATA_ARM_RISE`，整个任务期间举臂；
   - TLM `MaidBaseAnimation$10` 在 `isUsingItem() && 主手` 时把主手臂抬到约 84°；
     长矛蓄力必须持续 `startUsingItem`，所以整段冲锋都保持使用姿势。
   本轮新增 `MaidArmRiseMixin`（源头拒绝 true）和 `MaidSpearUsePoseMixin`
   （仅客户端把长矛的使用姿势报 false，服务端状态不变），并在 `MaidSpearSwingMixin`
   中加入带调用栈来源的诊断日志。

## 改动文件

- `promaid_src_neo/com/maidsmart/task/PlacedMarkNetworking.java`（364 行）：10 tick 对账 + 反向补齐。
- `promaid_src_neo/com/maidsmart/client/PlacedMarkClient.java`（218 行）：渲染前空气检查。
- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`（920 行）：
  RELAUNCH 保持高度、纳入擦地救起。
- `promaid_src_neo/com/maidsmart/mixin/MaidArmRiseMixin.java`（74 行，新增）。
- `promaid_src_neo/com/maidsmart/mixin/MaidSpearUsePoseMixin.java`（38 行，新增）。
- `promaid_src_neo/com/maidsmart/mixin/MaidSpearSwingMixin.java`（92 行）：诊断日志。
- `promaid_src_neo/mixins.promaid.json`、`tools/arch/arch_modules.py`、`normify-promaid/`：登记与行号同步。

## 验证

- Neo 编译：`EXITCODE=0`。
- 全部护栏：`FAIL 0 / WARN 0`；`check_mixin_targets` 校验 277 个注入点。
- 专服（测试服 mods 里临时放 `spearcore-1.21.1-neo-3.1.0.jar`）：
  `test_server.py neoforge1211` PASS；`test_maid_load.py neoforge1211` PASS；测试后已移除。
- Neo jar：18,137,248 字节，SHA256
  `5005A32433299B40ECA913250B6EA19E3B18916DEDE983AF19CB7EC5B955C606`。
- 已部署 1.21.1 实例，源包/目标包字节数一致（18,137,248）；目录内只有一个 promaid 包：
  `promaid-1.3.3-neoforge-1.21.1.jar`。Forge 源码树与两个 Forge 实例本轮零改动。

## 诊断日志

- 搜「描边」：`对账撤框 N 格（方块已消失/被替换）@(x,y,z)`、
  `客户端剔除已消失方块的描边 N 格（硬绑定）`。
- 搜「挥臂诊断」：`swing(1/2) 拦截/放行`、`setSwingingArms(true) 已拦截`，
  每条带 `来源=类名#方法名`，可直接看到还剩哪条链路在摆臂。

## 边界

- 仅 1.21.1 NeoForge；1.20.1 的镜像**等开发者验收批准后**再做。
- 客户端空气检查只覆盖视野内 96 格；更远处的陈旧条目会在靠近时被删除。
- 服务端对账每 10 tick 一次；方块被替换成**另一个非空气方块**时最多延迟 0.5 秒撤框。
- 隐藏使用姿势只在客户端，服务端的长矛蓄力/伤害/耐久完全不变。
- 若仍有第三方模组直接写模型骨骼，日志会给出调用来源，但需要针对该模组另做兼容。
- 版本号仍为 1.3.3（同号重打）。

## 验收

1. **描边**：把女仆搭的方块打掉/替换，金色框应在当帧或最多 0.5 秒内消失；新搭的方块应立即有框。
2. **二次点火**：长矛空袭打完一轮后，第二次点火应在滑翔中完成，不应先落地再起跳；
   低空时能看到她抬头把高度拉回约 4 格。
3. **手臂**：攻击模式持矛冲锋，蓄力/追击/后撤手臂保持常态；只在 spearcore 造成伤害的
   那一拍出现一次摆臂。若仍有动作，把「挥臂诊断」日志发来，里面会写明来源。
