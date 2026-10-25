# R1132 营火站位判定：头格用了 below / 只扫同层（仅 1.20.1）

## 排班

- **跑道**：C 缺陷修复（玩家实测"进了游戏，还是识别不出来，完全没有动作"；仅 1.20.1）。
- **范围**：1.21.1 玩家已确认无问题，不重建、不改动。
- **开工前读过**：玩家 17:27–17:31 的 `latest.log`、`MaidCookBehavior` 的
  `tick` / `stationStandSpot` / `processCampfire`、`MaidBrewBehavior.stationStandSpot`、
  1.20.1 的 `BlockPos.above/below` 与 `BlockState.isRedstoneConductor` 映射。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 复现"绑了营火但零动作" | 日志有 `cook start 台=营火`，但无 `Campfire placed=`、无 `cook scan` | ☑ |
| 2 | 定位站位判定 | `stationStandSpot` 返回 null → 营火分支静默解绑 → 重绑循环 | ☑ |
| 3 | 修头格与层数 | 头格改 `above()`；邻格同时扫本层与上一层；要求下方有可站面 | ☑ |
| 4 | 补日志 | 找不到站位时写 `cook unstand: ...` | ☑ |
| 5 | 同步修酿造台同款站位 | `MaidBrewBehavior` 同一份 helper | ☑ |
| 6 | Forge 编译/护栏/打包/部署/两测试/轮次文档 | 全绿 | ☑ |

## 取证（玩家 17:27–17:31 日志 + 存档区块）

- `17:27:28` 与 `17:29:39` 各有一次 `cook start: maid=大正女仆酒狐 台=营火 BlockPos{x=-415, y=-60, z=446}`
  ——**她已经认出台型并绑上了营火**（R1131 的日志改造生效）。
- 之后 2 分钟内**没有任何** `Campfire placed=`、`cook switch`、`cook scan`、`cook unstand`；
  她一直"任务占用中"却不动。
- 解析存档：营火 `(-415,-60,446)` 是 `lit=true`、四槽全空；**周围一圈 y=-60 全是草地**
  （营火与地面齐平：把草方块挖掉再摆进去），y=-59 才是可站的面。

## 根因（两处叠加）

1. **头格取错方向**：`stationStandSpot` 写的是
   `BlockState head = level.getBlockState(p.m_7495_())`——`m_7495_` 是 `below()`，不是 `above()`。
   于是判定变成"脚下（下方）是实心就跳过"：正常摆在草地上的营火，四个同层邻格的下方都是草地，
   **一个站位都挑不出来**。
2. **只扫同一层**：循环是 `station.offset(dx, 0, dz)`，永远只看营火所在那一层。
   玩家的营火与地面齐平，同层四邻都是草地（实心），上一层（`y=-59`）才是空位——
   即使方向对了也找不到。

后果链：`stand == null` → 营火分支静默解绑并漫游 → 2 秒后 `findStation` 又绑上 → 再解绑……
玩家看到的就是"绑上台了，但完全没动作"。

## 改动（仅 Forge 1.20.1）

- `MaidCookBehavior.stationStandSpot`：
  - 头格改为 `p.m_7494_()`（`above()`）；
  - 邻格循环 `dy = 0..1`（台子本层 + 上一层），覆盖"营火/台子与地面齐平或嵌进一格"；
  - 脚下/头格改用 `getCollisionShape().isEmpty()` 判空（原先用的 `isRedstoneConductor` 对玻璃/树叶等
    会误判为"能站"），并新增下方支撑判定 `state.isFaceSturdy(level, below, Direction.UP)`；
  - 头格也纳入危险方块过滤。
- 营火站位为 null 时补一条 `cook unstand: maid=... 营火 ... 四周（含上一层）没有可站的邻格 → 解绑换台`。
- `MaidBrewBehavior.stationStandSpot`：同款两处问题一并修正（同一份复制的 helper）。
- **1.21.1 树未改动。**

## 验证

- Forge 编译 `EXITCODE=0`。
- 14 项护栏 `FAIL 0 / WARN 0`。
- 打包 + 部署 4 实例 `match=True`（Neo 包未重建）。
- Forge 两套专服回归 PASS：`test_server 1201`、`test_maid_load 1201`。
- Forge jar：18,206,664 字节，SHA256
  `F879C3AFBBA0D20C1C20D66AD00C33D51DBCE8D65D3F66B76DA4292D6C75BA62`。
- **反证**：
  - 站位仍只挑台子**旁边**（本层或上一层），绝不把台子格当站位；营火上方那一格仍不站。
  - 炉子/烟熏炉/高炉的旧行为保留：找不到邻格时仍会退回"走到方块本身"，只是现在基本都能找到邻格。
  - 危险方块、工作区、其它女仆占用三条过滤原样保留。

## 边界

- 站位只能是台子的四邻（本层或上一层）；四周都是实心墙/岩浆/火时仍会解绑换台，并留下 `cook unstand`。
- 玩家这张图的场景（营火与草地齐平）现在会站到相邻草方块**顶上**（比营火高一格），不会踩进火里。
- 只改 1.20.1；Neo 树保持上一轮。
- 版本号仍为 **1.3.3**，同号重打；发布素材等验收后再写。

## 验收

1. 同一存档、同一座营火、女仆做「烧制」、背包有生肉：她走到营火旁边站定，日志出现
   `Campfire ... placed=1xbeef`，营火四槽里能看到肉；烤熟后掉落被她捡回。
2. 把营火摆在草地上（不挖坑）再试一次：同样能站到旁边（不再被"下方是实心"误判）。
3. 用酿造台做同样的验证：她站到台子旁边而不是踩在台子上，酿造链路有动作。
4. 故意把营火四周堵死：日志出现一次 `cook unstand`，她去换别的台，不会原地卡死。
