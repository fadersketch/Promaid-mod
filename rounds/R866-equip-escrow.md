# 实测八百六十六 · 穿戴装备托管（装备离开她身上就打标记，确认落定才销）

## 需求 / 来源

开发者实机反馈（1.20.1 存档，难二次复现）：

> 将女仆身上穿戴的钻石装备用鼠标移动到玩家的物品栏时，物品直接消失。
> 玩家可以用鼠标把装备从女仆的装备栏上面拿走。但随后使用鼠标光标拖着物品将光标上的装备放到
> 玩家物品栏时直接消失。身上的 4 件装备都是如此，全部丢失。

开发者当场给的**修法形状**（照做）：

> 我觉得需要给女仆身上穿戴的装备打一次标记。每次进行移动的时候必须要明确到最终的位置才可以把标记清掉。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树逐字镜像、临时脚本用完即删。

## 评估

- **跑道**：A 修 bug（P0，丢玩家财产）。
- **复现**：打开女仆界面 → 鼠标把某件**穿戴中的装备**（头/胸/腿/脚）从女仆槽拖到**光标** →
  再把光标那件放进**玩家物品栏** → 装备消失。事后换新装备重复同样操作**复现不了**（偶发，指向时序）。
- **既有「装备救回」为什么兜不住**（`ProMaidExtension.onMaidEquipmentLeave`，实测八百三十三 / 八百四十一 两轮演进）：
  现判据是「槽位一被清空，若那件**此刻不在任何玩家手里**（光标 / 背包）就在当拍塞回去，否则不救」。
  命中本 bug 的正是**「此刻在玩家手里」那一支**——
  - 玩家把装备拿起时它**在光标上** → `heldByAnyPlayer=true` → **当拍不救**（对，否则就是复制）；
  - 玩家随后把它放进背包那一下**万一真丢了**（本 bug），**槽位早已是空的**，`LivingEquipmentChangeEvent`
    只在「有→空」那一次补发（实体每 tick `detectEquipmentUpdates` 比较旧/新装备快照），
    **不会再补发第二次** → 没有任何一枪再来看它 → 装备永久消失。
  - 也就是说：旧逻辑是「**当拍快照式**」判定，天生看不见「离开后、落地前」这段空窗里的丢失。
- **日志证据**（`logs/promaid.log`，16:19 那一局）：58 条 `[装备离开]`、**0 条 `[装备救回]`** ——
  旧救回从未触发；本模组在那一局对女仆装备只做只读留痕（`MaidResyncCommand` 的补包全是从她身上**读**），
  没有一处删她装备。丢失发生在**她的槽位空掉之后**（光标→背包那一段），是我们的盲区，不是我们删的。
- **根因三问**：
  1. **复现**：拖出→光标→放进玩家物品栏，装备丢失（偶发）。
  2. **根因**：旧救回**当拍判定**——玩家拿在光标上时不救（正确），可一旦那件在后续搬运里丢了，
     槽位清空事件不会再补发，**没有任何后续检查**会发现它已失踪。
  3. **同类点**：所有「离开她身上、且中途经过玩家光标」的装备（4 件护甲 + 主/副手）都是同一空窗，
     本次一道托管一并覆盖。
- 查 `ARCHITECTURE-SWITCHES.md` / `BUG_DEBT.md`：`misc.equipRescue`（`MISC_EQUIP_RESCUE`）已是本类介入的**总闸**
  （默认开、随 DIY 关闭）；本次不再新增配置项，直接挂在这把闸下——**零新开关、零 lang 改动、零护栏基线漂移**。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 读透 `onMaidEquipmentLeave` 四道闸 + 实测八百三十三/八百四十一 两轮演进，确认旧判据是「当拍快照」 | 本文 §评估 | ☑ |
| 2 | 侦察 | 从 `logs/promaid.log` 实证旧救回 0 次触发（58 `[装备离开]` vs 0 `[装备救回]`），且本模组对装备只读 | 本文 §评估 | ☑ |
| 3 | 侦察 | javap 实证两树 API：`AbstractContainerMenu.getItems/getCarried`（Forge `m_38927_/m_142621_`、Neo `getItems/getCarried`）、`ItemEntity.getItem`（Forge `m_32055_`、Neo `getItem`） | 本次会话已核 | ☑ |
| 4 | 设计 | 定「托管」语义：离身即打标记；每 5 拍定位一次；**只有在最终位置确认过**（她容器/她装备槽/任意玩家界面与背包/附近地面/别的女仆）才销；光标上=搬运中→**续期不清**；到点还找不到→放回她身上 | 本文 §改法 | ☑ |
| 5 | 实现 | Forge：`EQUIP_ESCROW` 表 + `Escrow` 内部类 + `escrowTimer` + `tickEquipEscrow` + `locateEquip` + 注册改造 + `onMaidEquipmentLeave` 尾段重写 | 两树 | ☑ |
| 6 | 实现 | Neo：同改（Mojang 名镜像） | 两树 | ☑ |
| 7 | 镜像 | 逐文件核对 SRG↔Mojang（除 API 名外逐字节一致） | 引用计数比对 | ☑ |
| 8 | 验证 | 两树 `javac EXITCODE=0`；四护栏问题数不增；打包自检 | 构建输出 | ☑ |
| 9 | 留痕 | `rounds/RELEASES.tsv` 同号重打包留痕 + 更新本文 §验证 | 两处 | ☑ |
| 10 | 验收 | 交开发者实测（拖出→光标→放进背包不再丢；真丢仍能救回） | 待开闸 | ☐ |

## 改法

**总闸**：沿用 `misc.equipRescue`（`MISC_EQUIP_RESCUE`，默认开）。关掉 = 回原版（装备被清就是没了），
与实测八百五十九 一口径。**不新增配置项**。

1. **注册（`onMaidEquipmentLeave` 尾段重写）**：槽位被清空、女仆存活、`rescuable(from)` 成立时，
   把这件登记进托管表（不再当拍判定救不救）：
   - 表：`EQUIP_ESCROW = Map<女仆 UUID, List<Escrow>>`；`Escrow{slot, want=from.copy(), deadline, started}`。
   - **去重**：同一只女仆已有同 `want`（`sameIgnoringDurability`）的未销标记 → 不重复登记（防到点两次放回=复制）。
   - 留痕：`[装备托管] 拿走了 X，盯着它落定`。旧的 `[装备离开]` 留痕保留不动。
2. **每 5 拍核一次**（服务端 tick，空表零开销；插在 `MaidResyncCommand.tickAutoResync(server)` 之后）：
   `if (++this.escrowTimer >= 5) { this.escrowTimer = 0; tickEquipEscrow(server); }`。
3. **定位 `locateEquip(maid, want)`** 返回一个位置码（本文件内私有常量 `LOC_*`）：
   - `LOC_MAID`：她任一容器（`inMaidContainers`，含双手）**或**她任一装备槽（`getItemBySlot` 全 `EquipmentSlot`）；
   - `LOC_PLAYER`：任一玩家 `containerMenu.getItems()` 里（= 玩家背包 / 当前界面里的箱子 / 女仆界面里的她的背包格）；
   - `LOC_CURSOR`：任一玩家 `containerMenu.getCarried()` 上（拿起未放下）；
   - `LOC_GROUND`：她 24 格内的 `ItemEntity`（含任意玩家 / 任意女仆 24 格内，`matchOne` 数量≥）；
   - `LOC_NONE`：哪都没有。
4. **销标记 / 续期 / 救回**（`tickEquipEscrow` 逐条）：
   - `LOC_MAID / LOC_PLAYER / LOC_GROUND` → **落定，销标记**，留痕 `[装备落定]`；
   - `LOC_CURSOR` → **还在搬，续期**（`deadline = now + ESCROW_MS`）且**不清**——这正是旧逻辑看不见的空窗；
     同时记 `started`，超过 `ESCROW_MAX`（30 秒）兜底销掉（防玩家一直攥着导致表不收敛）；
   - `LOC_NONE` 且未到点 → 继续观察；
   - `LOC_NONE` 且到点 → **救回**：`putBack(maid, want, slot)`，装不下 `spawnAtLocation`，留痕 `[装备救回]`
     （与旧文案同口径，只是判据从「当拍」改成「离身 1.5 秒仍未落定」）；
   - 女仆不在名册（被收/被删/死了）→ 销标记，留痕 `[托管放弃]`（她已不在，无从放回）。
5. 版本号**不动**（1.3.2）。不写文案、不写 changelog。

### 为什么这样不会「复制」

- **只要那件在任何一个"最终位置"被找到就销标记、绝不放回** —— 玩家背包 / 当前界面 / 女仆容器与装备槽 / 地面
  （数量≥ 即算命中）全覆盖；到点放回**只在四处全查无**时才发生（= 真丢），与实测八百三十三 的口径一致。
- 「光标上」**不销、也不动光标**（只续期）—— 光标不是最终位置，玩家正攥着；此处不写 `setCarried`，
  避免与客户端界面状态打架。
- 去重登记 + 数量≥ + 除耐久外同 NBT（`sameIgnoringDurability`）→ 到点最多放回一次。

### 不做

- 不改 `putBack` / `rescuable` / `inMaidContainers` / `hasSame` / `sameIgnoringDurability`（复用）。
- 不动 `MaidResyncCommand`（那边全是从她身上读，无关）。
- 不新增配置项 / lang / 面板行；不动版本号。

## 触及文件

**改动**（两树各一份）：`com/maidsmart/ProMaidExtension.java`（唯一）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0`（Forge 88 警告，与上轮同档；Neo 同档） |
| 护栏 | `check_preset` FAIL 0 / WARN 2（配置项 601/607、DIY 关闭 170/174，与新基线一致；WARN 2 系既有 `broom.enable` 重路径）；`check_workflow` FAIL 0 / WARN 3（三条旧 WARN）；`check_comment_style` 927 文件 / **2262 处**（与基线同数）；`check_panel_coverage` FAIL 0 / WARN 0 |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge 17,576,393 B（`f63e4635…107eb3`）、Neo 17,615,292 B（`40965ff1…9a6f80`）；桌面 `Promaid_本体当前` 快照刷新一致 |
| 镜像 | 新增块按 SRG↔Mojang 归一后两树**逐字节一致**（`tools/_mirror.py` 归一比对：`MATCH`）；逐符号计数两树相同（`EQUIP_ESCROW` 5、`escrowTimer` 3、`registerEscrow` 2、`tickEquipEscrow` 2、`locateEquip` 2、`locName` 2、`ESCROW_MS` 3、`ESCROW_MAX` 2、`LOC_MAID` 6、`LOC_CURSOR` 3、`LOC_GROUND` 4） |
| 符号 | 两个 jar 的 `ProMaidExtension.class` 均含 `EQUIP_ESCROW/registerEscrow/tickEquipEscrow/locateEquip/LOC_CURSOR`，且**不再含** `heldByAnyPlayer/inPlayerPossession/inOwnerInventory`（死代码已删） |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |

**反证**：本轮把「离身后、落地前」这段空窗从「无人看管」改成「每 5 拍核一次」——
去掉修复（回到旧当拍判定）时，玩家在光标上取走装备那一下 `heldByAnyPlayer=true` → 不登记 → 后续丢失无人兜底；
装上后，同一路径会先登记 `[装备托管]`，落定打 `[装备落定]`、到点查无打 `[装备救回]`。
开发者实测前的日志关键词：`[装备托管]` / `[装备落定]` / `[装备救回]`。

## 版本与上传

- 版本保持 **1.3.2**；**不**部署、**不**跑回归、**不**发 release；同号重打按 WORKFLOW §5.2 留痕。

## 验收

**未验收**。

## 边界

- 托管只在「槽位有→空」那一刻登记；`rescuable` 之外（吃掉的最后一件 / 用坏的那件 / 药水桶烟花）不登记。
- 女仆被收/删/死时登记直接作废（无从放回），留痕说明，不硬塞。
- 玩家一直把装备攥在光标上：标记续期至多 30 秒后销掉，期间**不碰光标**；真正落定（背包/界面/地面）照常即刻销。
- 只覆盖**本模组认得的最终位置**；掉进其它模组自建存储这类极端情况到点会放回一件（与旧救回同风险档，
  但旧救回是"当拍必放"，本轮的观察窗反而更保守）。

## 台账

- **BUG_DEBT**：本轮把「离开后落地前」这段空窗纳入托管（实测八百六十六）；旧「装备救回」的当拍判定
  记录为已被本轮取代。
