# 实测八百四十三 · 拆完刷怪笼「副手镐没还回去、背包还多一把」的复制修复（两树）

> 流程见 `WORKFLOW.md`；本轮的 `## 排班` 是开工前置的硬门禁。

## 需求 / 来源

开发者原话（本轮起因，紧接实测八百四十二 之后）：

> 现在反而出现了更严重的问题。女仆在使用镐子挖刷怪笼的时候完以后，副手上的没有减少，背包里面又多出来一把。

即：拆完刷怪笼后，**借到副手的那把镐没有归还**（副手仍留着一把），而且**背包里同时多出一把** —— 净 +1，
可无限刷镐。注意这与实测八百四十一 修的那条**不是同一条**：那条要玩家在界面里手动取走，这条**不需要
任何玩家操作**，拆完就会发生。

## 评估（先在日志里把现场坐实，再谈改法）

**证据：NeoForge 实例 `D:\.minecraft\versions\1.21.1-NeoForge_21.1.250\logs\promaid.log`（2026-10-10）**

拆笼与两条装备留痕紧挨着出现（同一秒）：

```
[18:08:56] [刷怪笼] 功夫酒狐 拆掉了刷怪笼 (-70, -57, 49)（… → 直接拆除）
[18:08:56] [装备离开] 功夫酒狐 槽=OFFHAND 失去 1xminecraft:netherite_pickaxe | 她背包[… 24:1xminecraft:netherite_pickaxe …]
[18:08:56] [装备救回] 功夫酒狐 槽=OFFHAND 找回 1xminecraft:netherite_pickaxe（清空事件是它最后一份引用，已放回她身上）
```

**「失去」那一条自己的背包快照里，第 24 格就躺着一把同款 `netherite_pickaxe`** —— 也就是说：它明明还在她身上，
却仍被判成"丢了"并救回。17:13:29 / 17:16:24 / 18:08:26 / 18:08:56 都是这个形状，而同样形状的
17:13:38 / 17:13:43 / 18:08:54 却**没有**救回 —— 时有时无，正是下面这个判据的特征。

**根因（代码对照，两树同形）**

1. 还镐这条链 `MaidSpawnerTorchBehavior.restoreOffhand`（拆完 / 停手 / 切任务都会调）会
   `hands.setStackInSlot(1, EMPTY)` 把借来的镐从副手腾空、再 `insertItemStacked` 塞回背包。
   她原本副手多半是**空的**，于是这次腾空在下一 tick 由原版 `detectEquipmentUpdates` 补发的
   `LivingEquipmentChangeEvent` 是 `from=那把镐 / to=空` —— `onMaidEquipmentLeave` 开头那道
   「`to` 非空就返回」**拦不住**，于是走到救回判定。
2. 救回的第一道闸「还在不在她身上」= `inMaidContainers → hasSame`，其相等判据是
   `ItemHandlerHelper.canItemStacksStack(s, want)` —— 它**要求两件耐久完全相同**
   （可损坏物品的耐久存在 NBT 的 `Damage` 里，耐久不同 → NBT 不同 → 判不相等）。
3. 可事件里的 `from` 是原版**上一 tick 记下的副本**（`lastEquipment` 里那份），耐久是**扣之前**的旧值；
   而这一 tick 她刚好用这把镐**拆掉了方块**（`hurtAndBreak` 扣 1 点耐久），背包里那件的耐久是**扣之后**的新值。
   两者**差恰好 1** → 判成"她身上没有这件东西" → 救回 → 副手被塞回一把、背包里那把也还在 = **凭空多一把**。
4. **为什么时有时无**：`hurtAndBreak` 在耐久附魔（Unbreaking）下这一次可能一点都没扣 → 差 0 → 判相等 →
   不救；扣了就差 1 → 误救。日志里同一只女仆同一种镐，一会儿救一会儿不救，正是这条判据的指纹。
5. **同类点（同一轮一并堵）**：`inMaidContainers` 只看 背包 / 隐藏 / 任务 / 饰品，**没看她的两只手**。
   `MaidPlanting` 的「主手 ⇄ 副手换手」（`MaidPlanting:689` 把副手暂存物挪到主手并腾空副手）在这种闸下
   也会被当成"丢了" → 同款复制。这是同一类判据缺口，顺手补齐更省事。

**为什么不是实测八百四十一 那条**：八百四十一 改的是"玩家界面取走"那种（要人参与，且 `from` 与玩家背包
里那件耐久相同）；这条是**还镐**动作自身触发、靠"耐久差 1"骗过闸，与玩家无关。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 读玩家实例 `promaid.log`，把"拆完 → 失去 → 救回"三条按秒对齐，确认 `from` 与背包那件同款却仍被判"丢" | 本文 §评估（含原始日志行） | ☑ |
| 2 | 侦察 | 代码对照：`restoreOffhand` 的腾空 → `onMaidEquipmentLeave` 的闸序 → `inMaidContainers/hasSame/canItemStacksStack` 的相等语义 | 本文 §评估 1~3 | ☑ |
| 3 | 侦察 | grep 全部"动副手"的点，判断还有谁会踩同一判据（`MaidPlanting` 换手 / `MaidToolAutoEquip` 换盾 / TLM 藏手） | 本文 §评估 5 | ☑ |
| 4 | 实证 | `javap` 核 `ItemStack.setDamageValue` 的 SRG 名（要用"把耐久归零再比"的写法，不自己拼 NBT） | javap 输出 | ☑ |
| 5 | 实现 | `hasSame` 的相等判据改成「同物品 + 数量够 + **去掉耐久后**完全相同」（耐久是易变量，不参与"是不是同一件"） | 两树 `ProMaidExtension.java` | ☑ |
| 6 | 实现 | `inMaidContainers` 增查她的**两只手**（换到另一只手 ≠ 丢），堵掉换手类的同款复制 | 两树 `ProMaidExtension.java` | ☑ |
| 7 | 镜像 | Neo 树逐文件对齐（SRG↔Mojang；纯 Java/字符串逐字节一致） | 引用比对 | ☑ |
| 8 | 验证 | 两树 `javac EXITCODE=0`；四护栏问题数**不增**；打包自检 `MISSING: none` | 构建输出 | ☑ |
| 9 | 交付 | 打包 → 桌面快照；装本机 4 实例（`match=True`）；**回归按约定等开发者开闸** | deploy 输出 | ☑ |
| 10 | 留痕 | `RELEASES.tsv` 同号重打包留痕 + `BUG_DEBT` + 本文 §验证/§验收 | 三处 | ☑ |
| 11 | 验收 | 交开发者实测（重复"走到刷怪笼 → 拆掉"，看副手是否还留镐、背包是否 +1） | 等开发者 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法：让她连拆几只刷怪笼，然后
  ① 数背包里镐的总数（应与拆之前**一样**）；② 看她副手（应回到空 / 回到原本那件）；
  ③ 日志搜 `装备救回`：拆笼前后**不应**再出现跟着 `装备离开 … OFFHAND 失去 …_pickaxe` 的那一条
  （真丢装备时该条仍会出现）。

## 需求之外（本轮不做，记 §边界）

- 不动 `WORKFLOW.md` §6.1 的文案纪律；本轮是**修 bug 且是本版本（1.3.1）尚未推送内容的 bug**
  → 按开发者「本版本尚未推送内容的 bug 不进文档」的口径，**不写** release 素材。

## 改法（定稿）

1. `hasSame` 的相等判据：由 `ItemHandlerHelper.canItemStacksStack(s, want)`（含耐久）改为
   `sameIgnoringDurability(s, want)`：
   - 耐久**相同** → 直接走 `canItemStacksStack`（常见情形，一字不差）；
   - 耐久**不同** → 只有「两件都可损坏」才继续：各 `copy()` 一份、`setDamageValue(0)` 把耐久归零，
     再比 `canItemStacksStack`（附魔 / 自定义名等其它 NBT 照旧要比，所以"另一把不同附魔的镐"仍不算同一件）；
   - 不可损坏却耐久不同 → 直接不算。
2. `inMaidContainers` 追加 `hasSame(maid.getHandsInvWrapper(), want)` —— 换到另一只手不算丢。
3. **不做**：不改 `borrowOffhand` / `restoreOffhand` 的搬运动作本身（搬得没错，错的是"判是不是同一件"）；
   不加"我们自己刚挪过"的时间标记（治标，且要按每个调用点维护，容易漏）。

## 触及文件

- 代码（两树各 1 个）：`promaid_src|promaid_src_neo/com/maidsmart/ProMaidExtension.java`
  （`hasSame` 重写 + 新增 `sameIgnoringDurability`；`inMaidContainers` 加一行手部检查 + 注释）。
- 台账：`BUG_DEBT.md`（BD-10 追加"同款复现的第二种成因"或新开一行）、`rounds/RELEASES.tsv`。
- **新配置项 0 / 新 i18n key 0 / 新 mixin 0**。

## 验证

- **javap 实证（`client-1.20.1-20230612.114412-srg.jar`，`javap -p -c ItemStack`）**：
  `m_41721_(int)` 就是 `setDamageValue` —— 字节码是 `m_41784_()` 取 tag 后
  `CompoundTag.m_128405_("Damage", Math.max(0, v))`；**别混**：`m_41764_(int)` 才是 `setCount`
  （只写字段 `f_41587_`），`m_41769_/m_41774_` 是 grow/shrink。另核：`m_41773_`=`getDamageValue`、
  `m_41763_`=`isDamageableItem`、`m_41777_`=`copy`、`m_41783_`=`getTag`。
- 两树编译：`compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**。
- 四护栏问题数**不增**（与基线一致）：`check_comment_style` **2258/929**、`check_changelog` **100148**、
  `check_preset` FAIL **0**、`check_workflow` FAIL **0**（新 javadoc 已压到 8 行以内，不新增长注释块）。
- 打包：`MISSING: none`；`patched/promaid-1.3.1-forge-1.20.1.jar` 18,928,835 B（sha256
  `b8f840d5…b9f523`）、`patched/promaid-1.3.1-neoforge-1.21.1.jar` 18,966,718 B（`b2f1de01…ad7881`）。
- 装机：`deploy_all.py` 4 个目标全部 `match=True`。
- 台账/登记：`BUG_DEBT` 新增 **BD-11**（写明与 BD-10 的区别）；`rounds/RELEASES.tsv` 两条 1.3.1 行
  改填新 sha256、旧字节列入作废（第 8 次同号重打包），`check_workflow` 的"同名不同内容"FAIL 随之清零。
- **未做的验证**：按约定**不跑专服回归** —— 装机后停下等开发者实测（§4.1）。

## 验收

**未验收** —— 等开发者实测：连拆几只刷怪笼后，①背包镐总数与拆前一致；②副手回到空 / 原物；
③日志不再出现"拆完 → `OFFHAND 失去…pickaxe` → `装备救回…pickaxe`"这一对。

## 边界

- 不动已发布产物 / `_tmp_pub/` / `store/`（除 changelog 与台账）；不 commit / 不 push。
- **不升版本号**（仍 1.3.1；同号重打包按 §5.2 在 `RELEASES.tsv` 留痕）。
- 按开发者要求：修完**先只装本机等实测**，回归等开闸再跑。

## 台账

- `BUG_DEBT`：BD-10（装备救回复制）本轮补第二种成因——"还镐时耐久差 1 骗过『还在不在她身上』闸"，
  以及同类缺口"没查她的两只手"。
