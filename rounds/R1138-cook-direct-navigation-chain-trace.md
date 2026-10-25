# R1138 烧制寻路：改为直连导航 + 链路逐步日志

## 排班

- **跑道**：A 玩法修复（玩家实测："超平坦地形，离女仆 6 格左右远有一个高炉，女仆包中的可烧制物品
  只有粗铁矿，并且也有燃料。但把女仆放下的时候，女仆直接站着不动了，也没有任何汇报。直到我又在
  女仆的身边放一个炉子才工作……当时女仆是跟随模式，重新放置的炉子也是高炉。也就是说并不是识别不到
  高炉，而是寻路方面似乎出现了问题。导致女仆只会判定到身边的炉但是定位不到或者跑不到更远的。"
  并要求"给每一步链路都加上一条日志来定位问题"）。
- **范围**：`promaid_src` 与 `promaid_src_neo` 同步改。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 用实际日志确认卡在哪一步 | 反复 `cook stand-giveup ... 距台 4.27 格`，一步未动 | ☑ |
| 2 | 定位"为什么不走" | 烧制链路仍用 `WALK_TARGET`；同类问题项目内早已改直连导航 | ☑ |
| 3 | 去台子 / 闲逛改直连导航 `moveTo` | 6 格外的台能走过去 | ☑ |
| 4 | 到站停导航（`stop`，不再 `recomputePath`） | 不会到站后又被重算的路拖走 | ☑ |
| 5 | 链路每一步加日志 | `cook chain: step=...`，状态变化即记、同状态 5 秒一条 | ☑ |
| 6 | 编译 / 14 护栏 / 双 jar / 部署 / 四套专服测试 / 文档 | 全绿 | ☑ |

## 日志实证（1.21.1 NeoForge）

```
22:09:02  cook start: furnace=(-162,-60,789)
22:09:07  cook stand-giveup: 5 秒走不到标准站位 台=(-162,-60,789) 站位=(-162,-60,790) 距台 4.27 格 → 收成品换台
22:09:43  cook stand-giveup: ... 距台 4.27 格 → 收成品换台
22:09:46  cook stand-giveup: 台=(-162,-60,789) 站位=(-163,-60,789) 距台 4.27 格 → 收成品换台
22:09:52  cook round: fedSlot0=1 minecraft:raw_iron     ← 玩家在脚边补了一台后才发生
```

- 选台、配方匹配、占用都正常（否则不会有 `cook start: furnace=...`）。
- 她与目标站位只差 4.27 格，却连续 5 秒**一步没走**；这不是"走太慢"，是导航根本没执行。
- 在高炉旁边再放一台后立刻开工 → 说明"只认身边台"的表象，本质是"远的台走不过去"。

## 根因

烧制链路去台子/闲逛一直写 `WALK_TARGET` 记忆，等原版 `MoveToTargetSink` 消费。项目里
`MaidWorkBehavior` 在 v1.5.127 已经因为同一类问题（长距离被 sink 刹车、卡住时冷却、甚至根本不产生
位移）改成**直连导航 `getNavigation().moveTo`**；烧制没有跟着改。于是：

- 台子在站位格 1 格内 → 不需要走路，直接开工（历史用法都是把台放脚边，所以一直没暴露）；
- 台子在几格外 → 每 tick 写一次 `WALK_TARGET`，但没有任何位移；5 秒后被 R1137 的站位超时判"走不到"，
  收成品、给台子 30 秒冷却、换台，循环往复 → 玩家看到的就是"站着不动、也没汇报"。

## 改动

### 1. 走路统一走直连导航（两树）

- 新增 `walkTo(maid, target, speed)`：`setStill(false)` → `standUp` → 清 `WALK_TARGET` →
  `getNavigation().moveTo(target.x+0.5, y+0.5, z+0.5, speed)`；目标超过 14 格时先朝目标走 12 格接力
  （导航搜索盒约 16~17 格，与 `MaidWorkBehavior` 同口径）。
- 站位移动、`wander` 全部改走它；到站后 `getNavigation().stop()`（原来的 `recomputePath()` 会重新
  算路，可能把刚停下的她又拖走）。
- `standStallTicks` 保留：5 秒仍不到 → 收成品、30 秒冷却、换台。

### 2. 链路逐步日志

新增 `trace(maid, gameTime, step, detail)`：同一 `(step, detail)` **每 5 秒最多一条**，步骤一变立刻记。
日志格式：

```
cook chain: maid=<名字> step=<步骤> <细节>
```

覆盖的步骤：

| step | 含义 |
|---|---|
| `scan:wait` / `scan:none` / `scan:taken` / `scan:bind` | 没绑台时的扫描、找不到台、被占、绑定成功 |
| `check:block` / `check:area` / `check:inv` / `check:unlit` | 绑定校验：方块没了 / 出工作圈 / 没这型料 / 营火没点着 |
| `check:ok` | 绑定有效，进入站位 |
| `stand:none` / `stand:walk` / `stand:ok` / `stand:giveup` | 无站位 / 走向站位（带距离与 `navDone`）/ 到位 / 5 秒放弃 |
| `wait` | 已到位，等处理间隔（默认 40 tick） |
| `round` / `round:end` | 一轮开始（台型）/ 一轮结束（有无动作、台上是否还在烧） |
| `reselect:keep` / `reselect:same` / `reselect:switch` / `reselect:none` | 一轮结束后的重选结果 |
| `wander` | 没活可干时的闲逛点 |

配合既有的 `cook round: output/fuel/fed/nofeed/evict` 与 `cook stand-giveup` / `cook reselect` /
`cook watchdog`，现在每一步都有日志能对号入座。

## 验证

- 两树编译：`EXITCODE=0`。
- 14 项护栏：全部 `FAIL 0 / WARN 0`；`check_comment_style` 为 `PASS`；297 个 mixin 注入点。
- 打包：
  - `patched/promaid-1.3.3-forge-1.20.1.jar`，**18,211,337 字节**，
    SHA256 `F55885734B5C13C355C5AFA32871586F399AE479C5BB8C9779938439D550A53A`。
  - `patched/promaid-1.3.3-neoforge-1.21.1.jar`，**18,272,311 字节**，
    SHA256 `A2F5B1E7DBA2F558E1CA12D9C376E9FF283E8759A2EC65C49C9445A686759B7D`。
- 部署：4 个实例全部 `match=True`。
- 专服测试：`test_server` / `test_maid_load` × `1201` / `neoforge1211` 全部 `verdict: PASS`。

## 边界

- 直连导航的搜索盒约 16~17 格；超过 14 格先走 12 格接力，符合搜索范围。
- 仍要求先到标准站位才开工（R1137）；走不到 5 秒放弃换台（这条行为不变）。
- 链路日志默认开启，同状态 5 秒一条；密集操作时一轮最多 3~4 条，不影响正常日志阅读。
- 闲逛速度仍走「散步速度倍率」（默认 0.4），去台子速度 1.0。

## 验收

1. 超平坦、跟随模式、包里只有粗铁矿 + 燃料，6 格外放一台高炉：应走过去（日志
   `cook chain: step=stand:walk ... navDone=false`），到位后 `step=stand:ok` 并开始烧。
2. 台子就在脚边：行为与之前一致（`step=stand:ok` 后直接 `step=round`）。
3. 把台子围死/挖掉：5 秒后 `step=stand:giveup`（无站位则 `step=stand:none`），随后换台或闲逛。
4. 任何"站着不动"：日志里必然停在一个 `cook chain: step=...` 上，可直接看出卡在扫描、校验、
   走位、等间隔还是烧制轮次。
