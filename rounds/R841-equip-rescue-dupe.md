# 实测八百四十一 · 修「装备救回」误判：玩家从女仆界面取走副手物会被塞回来 → 刷物品

## 需求 / 来源

开发者报告（本轮起因，紧接实测八百四十）：

> 女仆在挖掘刷怪笼结束的时候。这个时候如果玩家打开女仆配置，从他的物品栏中把副手上的稿子给拿走。
> 那么女仆的副手上又会凭空刷新一个一模一样的稿子，这是一个恶性bug，会导致玩家刷物品。

要点：**玩家把女仆副手那件拿走 → 它会自己回到副手**，可反复取 → 净产出（刷物品）。

仍有效的约定：不跑回归、不写 changelog / 功能介绍文案、版本号不擅自变动（本轮**不动 1.3.1**，
同号重打包按 WORKFLOW §5.2 留痕）、纯 Java/字符串逐字节镜像。

## 评估

### 真凶：`ProMaidExtension.onMaidEquipmentLeave`（"装备救回"）的第二道闸是死的

该处是实测八百三十三加的兜底：槽位被清空时，只要"这件东西此刻不在她任何容器里、也不在主人背包里、
且不是设计上该消失的（食物/药水/烟花/桶/耐久见底）"，就**把它塞回原位**（副手槽清了就塞回副手）。

它的第一道闸是 `isPlayerDriven(stack)`：**看调用栈里有没有容器/网络帧**，若有 = 玩家在界面里操作 →
不救（东西在玩家那边，救回就是凭空复制）。这道闸**恒为 false**：

- javap 实证（Forge 1.20.1-47.4.23 的 `forge-...-client.jar` 与 1.21.1 NeoForge 的 `client-...-srg.jar`
  两侧都核过）：`LivingEquipmentChangeEvent` 是 `LivingEntity.m_21315_()`（`detectEquipmentUpdates`）
  → `m_21319_()`（`collectEquipmentChanges`，比较"上次装备快照"与"当前装备"）里**补发**的，
  调用方是**实体的每 tick 更新**——调用栈里**永远没有** `net.minecraft.world.inventory.*` /
  `net.minecraft.server.network.*` / `ServerPlayer` / `AbstractContainerMenu`。
- 所以玩家在女仆界面里取走副手那件后：**下一 tick** 女仆自己 tick 到 `detectEquipmentUpdates`，
  比较出"副手从有 → 空"，补发事件 → `isPlayerDriven` 返回 false（形同没写）→ 后三道闸也过 →
  `putBack` 把那件塞回副手。玩家手上那件还在 → 凭空多一件，可反复取。

### 为什么恰好是"拆刷怪笼时"的副手镐最容易中

拆笼是从**她自己的背包**借一把镐到副手（`MaidSpawnerTorchBehavior.borrowOffhand`：背包那格腾空、
镐进副手）。于是当玩家取走它时：

- `inMaidContainers`（背包/隐藏/任务/饰品）——**她的背包里已经没有这把镐了**（借走时腾空了）→ false；
- `inOwnerInventory`（主人背包）——玩家若把它拿在**光标上**（界面里拿起未放下），主人背包里还没有 → false；
- `rescuable`（镐不是消耗品、耐久没见底）→ true。

三道全过 → 塞回副手。普通副手物（玩家自己放的盾/剑）通常仍在主人背包 → 第二道闸拦住，所以只有
"背包来源 + 光标未落地"这个组合（拆笼借镐正好命中）能稳定复现。

### 结论：判据要改成"直接找那件在不在玩家手里"

一次界面取走只可能落到**两个地方**：**界面光标**（`player.containerMenu.getCarried()`）或**玩家背包**
（`player.getInventory()`）。两者都扫一遍，命中 = 没丢、不救——这才是 `isPlayerDriven` 想表达、
但因为事件是 tick 补发而永远表达不出来的意思。改完之后：

- 玩家取走（光标上 / 已入背包）→ 命中 → 不救 → **不复制**；
- 真·丢失（原版的补发清空、东西哪都没有）→ 不命中 → 照旧救回（实测八百三十三 的修复不退化）。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 读 `MaidSpawnerTorchBehavior` 借/还镐链路 + `ProMaidExtension.onMaidEquipmentLeave` 四道闸 | 本文 §评估 | ☑ |
| 2 | 侦察 | javap 实证 `LivingEquipmentChangeEvent` 的**补发点**（`detectEquipmentUpdates`→`collectEquipmentChanges`），确认 `isPlayerDriven` 恒假 | 本文 §评估 | ☑ |
| 3 | 设计 | 定判据：新增"这件此刻在任一玩家手里吗（光标 + 背包）"；保留其余三闸 | 本文 §改法 | ☑ |
| 4 | 实现 | Forge：`ProMaidExtension` 守卫改造（`isPlayerDriven` → `heldByAnyPlayer`）+ 注释改写（含实证） | 两树 | ☑ |
| 5 | 实现 | Neo：同改（Mojang 名 `containerMenu`/`getCarried()`/`players()`） | 两树 | ☑ |
| 6 | 镜像 | 逐文件核对 SRG↔Mojang（除 API 名外逐字节一致） | 引用计数比对 | ☑ |
| 7 | 验证 | 两树 `javac EXITCODE=0`；四护栏问题数不增；打包自检 | 构建输出 | ☑ |
| 8 | 交付 | 打包 → 桌面快照 → 装本机 4 实例（**不跑回归**） | deploy 输出 | ☑ |
| 9 | 留痕 | RELEASES.tsv 同号重打包留痕 + 本文 §验证/§验收 + 台账 | 三处 | ☑ |
| 10 | 验收 | 交开发者实测（拆笼借镐中取走副手镐 → 不再回填；真丢装备仍能救回） | 已验收 2026-10-10 | ☑ |

## 改法

1. `ProMaidExtension.onMaidEquipmentLeave` 的第二道闸：
   `isPlayerDriven(stack)` → `heldByAnyPlayer(maid, from)`（`stack` 仍留给①的留痕）。
2. 新增 `heldByAnyPlayer(maid, want)`：遍历她所在维度的全部 `ServerPlayer`，逐个问
   `inPlayerPossession(p, want)`（光标 `containerMenu.getCarried()` 或背包任一格）。
   保留原有 `inOwnerInventory`（主人在别的维度时兜一刀）。`hasSame` 的单件版复用为
   `matchOne(stack, want)`。
3. `isPlayerDriven` 删除（已证明恒假、且名字会误导后人）；注释改写为写明实证与正确判据。
4. 版本号**不动**（1.3.1）。不写文案、不写 changelog。

### 不做

- 不改 `MaidSpawnerTorchBehavior` 借/还镐逻辑（那边在"玩家取走"时是"放弃并还原原副手物"，
  本身不复制；复制发生在"救回"这一手）。
- 不动其它三闸（`maid.isAlive` / `rescuable` / `inMaidContainers`）——它们是"别救该消失的"，
  与本次误判无关。

## 触及文件

**改动**（两树各一份）：`com/maidsmart/ProMaidExtension.java`。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0`（Forge 88 警告，与上轮同档；Neo 同档） |
| `check_preset` | **FAIL 0 / WARN 2**（仅 `broom.enable` 重复定义，TD-08 旧账） |
| `check_comment_style` | 929 文件 / **2258 处**（与基线同数）。过程中的一次偏差已收口：`heldByAnyPlayer` 的 javadoc 一度 15 行（>8 记 MEMBER LONG-BLOCK，两树各 +1 → 2260），已压到 8 行 |
| `check_changelog` | 100148，与上轮同档（未写新条目） |
| `check_workflow` | **FAIL 0 / WARN 3**（三条旧 WARN）；RELEASES 同号重打包已登记、无"同名不同内容"FAIL |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge 18,928,200 B（`7e9ef616…13780b`）、Neo 18,966,101 B（`7fcd6235…16d93d`） |
| 镜像 | 新代码两树差异**仅** SRG↔Mojang 名（`m_9236_`↔`level()`、`m_6907_`↔`players()`、`f_36096_.m_142621_()`↔`containerMenu.getCarried()`、`m_150109_/m_6643_/m_8020_`↔`getInventory/getContainerSize/getItem`、`m_41619_/m_41613_`↔`isEmpty/getCount`、`canItemStacksStack`↔`isSameItemSameComponents`）；该文件两树本就有既有差异（Neo 独有若干处理器），不在本轮范围 |
| 部署 | `deploy_all.py`：4 实例 **match=True**（1.21.1-NeoForge、1.20.1-Forge、龙之冒险、pack1201） |
| 回归 | **未跑**（开发者闸门） |

## 验收

**已验收@2026-10-10** —— 开发者开闸「开始跑部署和回归，然后写 changelog 和 release 声明」；本轮专服回归
两加载器各两件全过、本机 4 实例 `match=True`；拆笼借镐期间在女仆界面取走副手镐，副手**不再**自己回填；
"真丢装备"（实测八百三十三 那一类）照旧能救回（新判据只认"在某个玩家手里 = 没丢"）。

## 边界

- 本次只堵"救回误判"这一条复制路径；`MaidSpawnerTorchBehavior` 的借还逻辑与玩家界面取用之间的
  时序（她发现副手空了会放弃并还原原副手物）保持不变。
- 判据是"任一玩家此刻持有"，所以玩家把东西拿在光标上、或已放进背包，都会拦住救回；玩家把它丢掉
  之后再触发的事件已过（事件每 tick 只在"有→空"那一次补发），不会重复救。

## 台账

- **BUG_DEBT**：新增一条（本处误判已在本轮修掉，登记为已清@实测八百四十一）。
