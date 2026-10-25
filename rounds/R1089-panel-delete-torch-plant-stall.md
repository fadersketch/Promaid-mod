# R1089 面板「删除」死键 / 火把种树避开垫块 / 卡死换目标提速

## 排班

- **跑道**：缺陷修复（两树镜像）。
- **为什么这轮先做修复**：三条都是玩家实机反馈、改动面小且互不影响；BD-04 生产链
  专项审计仍按原计划独立排期。
- **开工前读过**：`PromaidConfigScreen`（`MinableList` / `MinableEntry` /
  `removeMinable` / 屏幕级 `mouseClicked`）、`MaidTorchPlacerBehavior`、
  `MaidPlanting`、`PlacedBlockTracker`（`isAnyPlaced`）、`MaidWorkBehavior`
  （`stallWatchdog` / `WALK_STILL_TICKS`）、`MaidMineBehavior`（`MINE_WEDGE_TICKS`）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 名单列表「删除」改成屏幕级命中 | 点按钮必定移出并留一行聊天反馈 | ☑ |
| 2 | 火把/种树排除自己搭的临时方块 | 支撑格/土块在追踪表里就不放 | ☑ |
| 3 | 卡死换目标提速 | 8 秒 → 3 秒 | ☑ |
| 4 | 编译、护栏、重打、部署、测试 | 两树 `EXITCODE=0`；14 护栏 `FAIL 0`；四套测试 PASS | ☑ |

## 一、目标矿物列表的「删除」按了没反应

### 现象

矿表子页右侧每行一个「删除」（障碍物/木材页同款）。点击后列表不变、配置不变，
也没有任何提示；网格图标右上角的小叉是好的。

### 原因

那一行的按钮是 `ObjectSelectionList.Entry` 内部自建的 `Button`，没有加进屏幕
`children`：渲染时手动同步坐标，点击则依赖 `Entry.mouseClicked` 被列表转发后再
命中按钮。这条转发链在实机上不可靠——按钮画得出来、悬停也亮，但点击落不到条目上，
整行静默。网格小叉走的是屏幕自己的 `mouseClicked` 坐标判定，所以一直是好的。

### 修法

不再依赖条目转发：矿表子页把列表的屏幕矩形记下来（`mineListX/Y/W/H`），在屏幕的
`mouseClicked` 里按与渲染完全相同的几何直接命中：

- 行号 = `floor((mouseY - 列表Y + 滚动量 - 4) / 22)`（行高 22、顶部内边距 4，与
  `AbstractSelectionList.getEntryAtPosition` 同口径）；
- 「删除」列 = 列表左缘 + 行宽 − 62，宽 56px（与渲染同一个 `left + rowWidth - 62`）；
- 命中就取该行的**原始条目**（`MinableList.entryAtRow`）交给原来的 `removeMinable`
  ——删除逻辑、回退快照、矿表缓存刷新一个字不改。

删除后补一行聊天提示「已移出 <id>」，点没点中一眼能看出来；条目内的按钮保留，
仍负责画与悬停，不再承担点击。

## 二、插火把 / 种树不要放在自己搭的方块上

挖矿垫脚、搭路、自保搭高的方块统一登记在 `PlacedBlockTracker`，寿命约 10 秒，
到期会连同上面的附着物一起回收。旧版火把只判「脚下是实体方块」，树苗只判
「脚下是泥土/草方块」，于是她可能：

- 把火把插在自己刚垫的石头上 → 10 秒后垫块回收，火把一起消失；
- 把树苗种在自己垫的泥土上 → 苗跟着垫块没。

现在两处都多一条：支撑格 / 土块在 `PlacedBlockTracker.isAnyPlaced` 里 → 跳过
（火把计入「放不了」诊断，树苗换下一个候选格）。玩家永久放置的方块不在追踪表里，
不受影响。

## 三、卡住后换目标太慢

`MaidWorkBehavior.stallWatchdog` 的判据是「同一目标 + 同一双脚格 + 挖掘进度一字未动」，
旧阈值 160 拍 = 8 秒。玩家看到的是「她站着不动快十秒才换地方」，观感像坏了。

改为 60 拍 = 3 秒。安全性：

- 真在挖时 `destroyProgress` 每拍都在变，不会误判；
- 分帧扫描进行中由 `stallWatchdogSuppressed` 豁免（站着等扫描是正常的）；
- 出范围目标另有 `MINE_WEDGE_TICKS`（40 拍 = 2 秒）的「够不着且没挪窝」先手；
- 弃掉的目标进 30 秒短排，下一拍立刻重扫，不会在同一块上反复。

## 改动文件

- `promaid_src/com/maidsmart/config/PromaidConfigScreen.java`
- `promaid_src_neo/com/maidsmart/config/PromaidConfigScreen.java`
- `promaid_src/com/maidsmart/combat/MaidTorchPlacerBehavior.java`
- `promaid_src_neo/com/maidsmart/combat/MaidTorchPlacerBehavior.java`
- `promaid_src/com/maidsmart/task/MaidPlanting.java`
- `promaid_src_neo/com/maidsmart/task/MaidPlanting.java`
- `promaid_src/com/maidsmart/task/MaidWorkBehavior.java`
- `promaid_src_neo/com/maidsmart/task/MaidWorkBehavior.java`

## 验证

- Forge / Neo 编译均 `EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`（注入点 285）。
- 专服回归：`test_server.py 1201` PASS、`test_maid_load.py 1201` PASS、
  `test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS。
- Forge jar：18,136,367 字节，SHA256
  `FFE3D74AA0CF5428AEB967BBADD431A899F491C1B97B7FE4FE908632062B22FA`。
- Neo jar：18,196,182 字节，SHA256
  `AA6753DFB914513F6FFCF5EDA35BBCB2521534A86B08F269B2321BD355C13DE4`。
- 已部署 4 实例，`match=True`。

## 边界

- 屏幕级命中只拦「删除」那一列（行宽 −62 到 −6），点行内其他地方仍是列表自己的
  选择行为；网格小叉与「一键删除」等原有入口全部不变。
- 火把/种树只排除**本模组追踪的临时垫块**；玩家自己放的石头/泥土是永久的，照常
  可以插火把、种树。
- 3 秒换目标只针对「完全没进展」；正常挖掘、分帧扫描、搭高冷却都不会被这条打断。
- 面板删除的改动在关闭面板时随 `SPEC.save()` 落盘（与其它开关一致）。

## 验收

1. 目标矿物页点任意一行的「删除」：该行立即消失，聊天栏出现「已移出 <id>」；
   点「↩ 回退」能恢复；关面板再开仍然不在。
2. 障碍物页和伐木页的「删除」同样生效（共用同一入口）。
3. 让她挖矿垫高后在同一位置插火把：她不会把火把插在刚垫的方块上（日志里
   「插火把」那一行会把该格记进「放不了」）；垫块回收时火把不受牵连。
4. 让她在垫过的泥土上种树：会换一个天然土块，不把苗种在 10 秒后消失的垫块上。
5. 挖矿时人为堵住她的路：约 3 秒后她换目标并提示「这块我够不到，先换个地方找找～」，
   不再站 8 秒。
