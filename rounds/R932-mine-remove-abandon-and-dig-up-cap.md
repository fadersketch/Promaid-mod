# 实测九百三十二 挖矿删掉「弃置」＋补上「垫不上去就先挖开头顶」

> 跑道：**行为整改**。开发者指令（原话）：「首先要把弃置这个东西给去掉。然后我有一个疑问，为什么会有够不着的情况？
> 明明手中的挖掘物品可以挖开所有挡路的方块。自己的包里面也有足够的垫脚方块。那么不可能出现够不着的情况啊，
> 无论如何应该都能够采取行动。真正够不着的情况应该是挖不开或者没有可以垫的方块。」
> 上一轮：`rounds/R931-mine-blocker-wedge-and-landing-logs.md`（九百三十一 补卡住判据 + 降落留痕）。

## 排班

- **本轮主项**：①按开发者口径**删掉挖矿的「弃置」**（够不着就丢目标 / 记 30 秒短排 / 连弃 3 次换框）；②补上缺的**动作**——
  「垫不上去（头顶被岩层压住）就先挖开头顶那块」，让"够不着"永远有动作可做。版本号仍 **1.3.2**。
- 步骤：改 Forge → 镜像 Neo（同一份模板 + 名字映射，逐锚点断言唯一）→ 两树 `javac EXITCODE=0` → 全护栏 `FAIL 0` → 打 jar → 部署 4 实例 `match=True`。
- 谁验收：开发者（本轮部署后等实测）。

## 需求 / 来源

开发者的疑问：她有能挖开一切挡路方块的镐、背包里也有垫脚方块，**为什么会有"够不着"？** 结论是——
"够不着"不是矿真的到不了，而是**走位逻辑少了一个动作**（下面 `## 定位与改法 1` 有实机日志坐实）。
既然缺的是动作，那么"够不着就弃置"就是拿放弃去掩盖缺陷；开发者要求删掉它、把动作补全。

## 定位与改法

### 1. 「够不着」到底是怎么发生的（实机日志坐实，NeoForge 实例 12:49:39→12:50:14）

```
12:49:45.959 work walk stuck: target=(-1399,13,-699) stand=(-1399,11,-699) feet=(-1398,8,-697) dist=4 path=false -> stop+retarget
12:49:46.957 mine give-up: reason=2秒没挪窝 target=(-1399,13,-699) dist=5 feet=(-1398,8,-697) progress=0.0
12:49:51.158 mine box unreachable (3 连弃置、零破坏) → fresh box: anchor=(-1400,0,-677)
12:49:52.257 work exit: … reason=mine 全量扫描完成 found=1154 best=(-1398,12,-700)   ← 又挑回同一簇矿
12:49:52.356 work walk stuck: target=(-1399,13,-699) … feet=(-1398,8,-697) path=false
12:49:54.308 mine give-up: reason=2秒没挪窝 target=(-1399,13,-699) …
（12:50:01 / 12:50:09 / 12:50:18 再各来一遍；她脚下的 y 从 12:49:45 到 12:50:23 一模一样）
```

读法：矿在 `y=13`，她的脚在 `y=8`（**高 5 格**）。`approachOre` 走"目标比她高"那一支：

1. 先 `pillarUpStep` 垫高——**被拒**。它要求放置格上方两格不能是"会窒息的实心方块"（`suffocates`），
   而她在矿洞里、头顶就是岩层（`y=9/10` 都是实心）⇒ 返回 false。**她不会去挖开那两格。**
2. 于是走 `firstOreAbove` 兜底：把正上方那块矿当目标，沿矿柱往下找"可站的空气格"——找到
   `stand=(-1399,11,-699)`（岩层中间的一个孤立空腔）⇒ 让导航走过去。**那个空腔没有路**
   （`path=false`），导航永远失败。
3. 剩下唯一动作是"站在原地重发导航"。2 秒后（九百三十一 加的）判"没挪窝"→ **弃置** → 换下一块 →
   换来的还是同一簇矿 → 无限循环。

**结论：她手上明明有镐，却从没想过"把头顶那两格挖掉"**——这就是"够不着"的全部原因。
`pillarUpStep`（垫方块）和 `walkTo`（走过去）两条路都断在"头顶是实心岩石"上，而**挖开它**这条既简单又
必然可行的路在代码里根本不存在。

### 2. 改法：删「弃置」+ 补「挖开头顶」

**删（两树）**——挖矿里的"弃置"整体取消：

| 删除项 | 原用途 |
|---|---|
| `wedgeAbandon()`（含 `wedgeStreak` / `lastWedgeAt` / `stuckTicks` / `lastStuckPos` / `stuckTarget` / `MINE_WEDGE_TICKS`） | 脚下格 2 秒没动就弃置；连弃 3 次换框 |
| `targetTimeoutTicks()` + tick 里的"锁定超时够不着 → 弃置重选"块 | 超时丢掉够不着的目标 |
| `abandon()` 方法 | 写"上一格"`abandonedPos` + `RECENT_DISCARD` 30 秒短排 |
| 「视线被挡 → 弃置」块 | 关闭透视时看不见目标就丢 |
| `findOre`/`fullScanOres`/`pickFromCache`/抬头兜底 里的 `RECENT_DISCARD`、`abandonedPos` 读取 | 弃置记忆的消费端 |
| 挖掉一块后的 `abandonedPos`/`RECENT_DISCARD` 清理、`wedgeStreak` 清零 | 弃置记忆的清理端 |

**保留（这是开发者点名的合法走不到）**：`blockedOres` 硬挡路（箱子/机器/基岩这类**挖不开**的）
——报点 + 持续排除；以及 `硬度≤0/不可破坏`、`目标已不是矿`、`锚点解析不出来` 三条（都不是"弃置"，
是"这块不能挖 / 没有锚点"）。

> 说明：`TARGET_SINCE` 的表本身留在基类（伐木还在用），挖矿只剩写入。

**补（两树）**——新动作 `digUpCap(level, maid)`：目标在她上方、`pillarUpStep` 又失败时，把脚下
**正上方第一块可挖开的方块**（`isOpenStone`，不含基岩/箱子/机器）当临时目标挖掉——下一拍头顶空了、
垫高自然成立，**一格一格开天窗往上走**。挖掉后自动走既有连锁（同族开路石一次破 16 块），攀升很快。
调用点在 `approachOre` 的 `dy >= 1` 分支：`pillarUpStep` 之后、`firstOreAbove` 兜底之前。
正上方就是矿（那是正常目标）或那块挖不开时返回 false，交回原逻辑——**挖不开的那条才是唯一合法的走不到**。

### 3. 这样之后，"够不着"只剩两种（与开发者口径一致）

- **挖不开**：头顶是基岩/箱子/机器（`isOpenStone` 为假）→ `digUpCap` 返回 false，且射线上的硬挡块由
  「硬挡路」接管；或矿本身不可破坏（`硬度≤0`）。
- **没有可以垫的方块**：`pillarUpStep` 拿不到料 → 但此时 `digUpCap` 仍在（**挖开头顶不需要方块**），
  所以她照样能上去；真正"没料又挖不开"才会停。

## 触及文件

- `promaid_src` / `promaid_src_neo` × `com/maidsmart/task/MaidMineBehavior.java`（删 3 个方法 + 13 处锚点改写 + 新增 `digUpCap`）
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行 sha256 + 第七十一次同号重打）
- `rounds/R932-*.md`（本文件）

## 验证

- **两树编译**：`compile_promaid.bat` → `EXITCODE=0`；`compile_neo.bat` → `EXITCODE=0`。
  （首轮脚本两处映射笔误当场中止并修正：`GBS`/`BP` 占位符多带了 `level.`/`maid.` 前缀。）
- **残留自查**：两树全文再搜 `wedgeAbandon|abandon(|abandonedPos|RECENT_DISCARD|targetTimeoutTicks|MINE_WEDGE_TICKS|wedgeStreak|stuckTicks` → **0 命中**；只剩 `blockedOres`（硬挡路）与 `giveUpLog`（锚点/硬挡路留痕）。
- **全护栏**：`check_comment_style` / `check_catch_swallow` / `check_comment_dup` / `check_dev_voice` /
  `check_i18n_keys` / `check_panel_coverage` / `check_preset` / `check_reflection` / `check_tree_mirror` /
  `check_arch` 全部 **FAIL 0 / WARN 0**（`check_preset` DIY 基线仍 **172/176**——本轮不增删开关）；
  `check_workflow` **FAIL 0 / WARN 3**（历史遗留）。
- **两个 jar**：forge **17584709** 字节（sha256 `FFE7DB26…28ACF7`）；neoforge **17621086** 字节（`E3F5BF5E…598D31`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201）；旧包备份在 `patched/backup_old/`。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行 sha256 更新为新字节，并追加「第七十一次同号重打」；
  九百三十一 的旧字节 `32620797…0A20D1`（forge）/ `DB8111B6…9FEEDE`（neo）作废。
- **未跑实机回归**。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- **怎么看**：让她去挖矿（尤其"矿在头顶几格、头顶是岩层"的地形），在 `logs/latest.log` 里看：
  1. **不再有** `mine give-up:`（除 `硬挡路` / `锚点解析不出来` 外）与 `mine box unreachable`——弃置已删。
  2. 新日志 `work exit: … reason=mine 头顶压住挖开` → 她正在**挖开头顶那格**往上开天窗（正常动作，说明补的动作在跑）。
  3. 紧接着应看到 `mine chain burst`（同族开路石一次破 16 块，天窗开得很快）与 `mine 挖掉一块`。
  4. 这一轮之后遇到同一地形，应表现为**持续动作**（挖开头顶 → 垫高 → 再挖开头顶……直到够到矿），
     而不是站住不动。
  - 若仍出现"她站着不动"，请把 `work exit:`（尤其 `mine 够不着-继续推进` 的 `dist`）、
    `work walk stuck`、`mine give-up` 三段贴回来。
- 判定：本轮按开发者口径删弃置 + 补动作；是否真能一路挖上去，以这次实测为准。

## 边界（没动什么）

- **只改挖矿**（`MaidMineBehavior`）。伐木（`MaidWoodBehavior`）的弃置路径**未动**——伐木面对的树不在"头顶岩层"这类几何里，开发者本轮点的是挖矿；要不要同样处理，等指令。
- **基类 `MaidWorkBehavior` 未动**：`RECENT_DISCARD` / `abandonedPos` / `TARGET_SINCE` 是挖矿与伐木共用的表，伐木仍在用，故只删挖矿侧的读写，不删声明。
- **保留的"合法走不到"**：`blockedOres` 硬挡路（挖不开）、`硬度≤0`、`目标已不是矿`、`锚点解析不出来`。
- **遗留**：配置项 `mine.targetTimeout` 随 `targetTimeoutTicks()` 一起失去了读取方（键保留在配置/面板/i18n 里，未删）——要不要连带清掉这条开关，等指令。
- 版本号仍 **1.3.2**；DIY 关闭条数基线仍 **172/176**。
- 临时脚本与日志（`tools/_r932_*.py`、`_r932_*.log`）**已删**；仓库根目录既有的 `_*` 文件与预置目录未动。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第七十一次同号重打。
- `DEBT.md`：不新增条目——本轮是"删一条被误导的容错 + 补一个缺失动作"。
