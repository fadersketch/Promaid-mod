# R1100 指标石启动条件：主人包 + 女仆包 + 全部附属容器

## 排班

- **跑道**：C 功能（两树镜像）。
- **开工前读过**：`IndexStoneService`、`IndexStoneBuildBehavior`、`IndexStoneItem`、
  `BuildContainerFetchBehavior`、`BuildContainerSource`、`BlueprintMaterials.deliverToMaid`、
  `MaidExtraContainer`、`MaidBuildBlockFilter`（含 `isBlacklistedBuildBlock` / `takeBuildBlock`）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 确认旧版"启动前提是她的背包"的来历 | 绑定不查料；取材只问"她的包 → 主人包"，附属容器漏掉 | ☑ |
| 1 | 启动前扫三处来源 | `hasAnyBuildMaterial`：主人包 / 她的双手背包 / 全部附属容器 | ☑ |
| 2 | 一件都没有 → 拒绝绑定 | 不切任务、不占锁定框，给出原因文案 | ☑ |
| 3 | 取料补上附属容器 | `resolveMaterial` 在"她的包"之后先问额外容器，再问主人 | ☑ |
| 4 | 编译与护栏 | 两树编译 `EXITCODE=0`；14 护栏 `FAIL 0` | ☑ |
| 5 | 测试与部署 | 四套专服测试 PASS；双 jar；4 实例 `match=True` | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

1. "女仆启动指标石的前提是背包里有方块，但是实际的搭建过程中是可以从主人的背包里面拿方块的。
   所以说实际的启动条件是扫一下主人的包和女仆的包+所有附属容器。有了就拿，用什么搭建走建造黑名单。"

## 评估

旧版的取料链是"她的双手/背包 → 主人背包"，**少了她的附属容器**（精妙背包 / 旅行者背包，
TLM 的 `MaidExtraContainer` 体系，挂在饰品栏里）。补支撑那一支（`takeOneFor`）本来就会先问
额外容器，主材这一支却漏了——于是"装了精妙背包、方块都在里面"时，主材一直取不到。

另外绑定本身不查材料：没料也会切进 `maid_smart:index_build` 并占住锁定框，然后每 5 秒提示
"主人给我一些方块吧"。玩家看到的"启动前提是她的背包里有方块"，就是"只有她的背包能被主材链
当场用上"这一件事。

## 改动（两树镜像）

- `IndexStoneBuildBehavior`（新增 `hasAnyBuildMaterial`）：绑定前扫三处——
  主人的 `Inventory`、她的 `getHandsInvWrapper()` + `getAvailableBackpackInv()`、
  `MaidExtraContainer.contains(...)`（额外容器是"取出 1 件立刻放回"的只读探针）。
  判定一律走 `MaidBuildBlockFilter.isUsableBuildStack(level, pos)`，与真正取料**同一个重载**：
  下落方块 / TNT / 床门高植物 / 危险方块 / 无碰撞 / 可替换方块 / 玩家那张「搭方块禁用名单」/
  「只用原版天然方块」全部照旧生效。创造模式的主人视为无限材料。
- `IndexStoneService.bindOrUnbind`：锁定检查之后、换绑/释放旧女仆**之前**插入启动门——
  三处都没有可用方块就拒绝，提示"你的背包、她的背包和她的附加容器里都没有"，不切任务。
  绑定成功文案同步改成"材料从她背包/附加容器取，不够从你背包取"。
- `IndexStoneBuildBehavior.resolveMaterial`：她自己的双手/背包没有 → `pullFromExtraContainers`
  （`MaidExtraContainer.pull`，一次搬一组 64 件进背包）→ 再取一次 → 仍没有才问主人背包 →
  创造模式兜底。补支撑的 `takeOneFor` 本来就先问额外容器，不动。

## 验证

- Neo 编译：`NEO_OK`；Forge 编译：`FORGE_OK`。
- 14 项护栏：`FAIL 0 / WARN 0`。
- 专服回归：`test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS、
  `test_server.py 1201` PASS、`test_maid_load.py 1201` PASS。
- Neo jar：18,216,387 字节，SHA256
  `D634161CACC8D2301FEC3025E884162DD59F798BBA845087DD720E9CEB896A24`。
- Forge jar：18,154,984 字节，SHA256
  `7766E72DB0A48E61DFB385996A844001268DE0177D432201B793757119D16BD6`。
- 已部署 4 实例，源包/目标包字节数一致（`match=True`）。

## 边界

- 只扫"主人的物品栏 + 她身上（双手/背包/额外容器）"，**不**扫工地附近的箱子——箱子取料仍是
  蓝图建造那条 `BuildContainerFetchBehavior` 的事，指标石不改变该口径。
- 启动门只看"有没有至少一件可用方块"；具体用哪种仍是"数量最多者优先"（`takeBuildBlock`）。
- 建造中途材料耗尽仍按旧口径等待（每 40 次尝试重取一次、每 100 次提醒一次），不烂尾。
- 玩家在锁定后、绑定前把方块全收走 → 拒绝绑定；补料后重新右击女仆即可。
- 探针自身抛异常时**放行**（返回 true），不让一次异常把指标石整条掐死。

## 验收

1. **只有主人背包有方块**：绑定即开建，第一次取料会把主人背包里最多的一种搬一组给她。
2. **只有她的精妙背包里有方块**：绑定即开建，主材从额外容器搬出来用（旧版会干等）。
3. **三处都没有可用方块**：右击女仆被拒绝，提示三处都没有，她的任务与锁定框都不变。
4. **只有被禁用名单拦住的方块**（如沙子、关掉"只用原版天然方块"后加了模组方块进禁用名单）：
   同样视为"没有可用方块"，绑定被拒。
