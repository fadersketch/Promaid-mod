# R1087 扫帚起飞口径 / AI 提示词记录 / 扫帚复制审计

## 排班

- **跑道**：缺陷修复（两树镜像）+ 只读审计。
- **为什么这轮先做修复**：本轮是三条玩家反馈合并成的修复轮，改动集中在扫帚门禁与
  AI 聊天历史；未同时清 BD-04（挖矿/伐木/农场生产链专项审计）——那条需要独立排期、
  逐项实机验证，硬塞进本轮会让验收面翻倍、且与本次改动没有交叠。
- **开工前读过**：`MaidBroomBehavior` / `MaidBroomKit` / `MaidBroomDrive`、
  `EntityBroomMaidTravelMixin`、`ProactiveDialogueManager` / `ChatInfoUtil` /
  `MaidInternalHistoryMixin`；TLM 的 `EntityBroom` / `AbstractEntityFromItem` /
  `MaidAIChatData` / `MaidAIChatManager` 字节码。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 扫帚起飞门禁统一 | 两条入口都要求「扫帚 + 远程武器 + 弹药」 | ☑ |
| 2 | AI 提示词记录溯源与清理 | 读档时清掉旧版写进历史的提示词 | ☑ |
| 3 | 扫帚复制审计 | 给出物品账目与全部入口，无复现路径 | ☑ |
| 4 | 编译与护栏 | 两树 `EXITCODE=0`；14 护栏 `FAIL 0` | ☑ |
| 5 | 重打、部署、测试 | 4 实例 `match=True`；四套测试 PASS | ☑ |

## 一、扫帚模式两条入口口径不一致

### 根因

起飞判定 `MaidBroomKit.isModeActive` = 扫帚 + 远程武器 + 弹药，本来是对的，但旧版
只在 ④.5「原地抬高 1 格」这一档调用它：

- 主人把世界里的扫帚放到她脚下、把她推上去：她已经骑着扫帚，行为链跳过 ②
  「身上没扫帚 → 先去找」里的 `armed()`（武器 + 弹药）预检；③ 的下鞍判据只要求
  「扫帚 + 远程武器」（`canStayMounted`，刻意不含弹药），于是她留在扫帚上；
  接着 ⑤ 接敌 / ⑥ 跟随巡逻**无条件**写推进意图——只要手上有远程武器，没弹药也照样飞。
- 扫帚在她背包里：必须先过 ② 的 `armed()`（武器 + 弹药），才会取出来骑。

### 修法

把 `isModeActive` 提到 ④.4（玩家驾驶让位）之后、所有位移之前：

- 缺件 → `hoverInPlace` 原地悬停，**不下鞍、不丢高度**（保留「弹匣打空别把她摔下去」
  的旧口径），补齐后下一 tick 自动继续；
- 玩家在驾驶扫帚时仍不算——方向盘在玩家手里，她只负责开火；
- ④.5 内部的重复判据删除，起飞相位只负责抬升。

`ammoOk` 仍旧走 `hasAmmoForWeapon || hasUsableRangedWeaponAny`（弹匣空但背包有
对得上的弹药、或换弹能解决，都算齐备），所以冲锋枪/狙击枪的「这一拍弹=无」不会把她
钉在地面；被挡住的只有「确实一点弹药都没有」。

## 二、聊天记录里的「提示词」

### 来源

截图里的蓝底消息不是玩家输入的，也不是 TLM 生成的，而是本模组主动对话的
**内部触发**：主人问过问题、女仆没有答上时，`ProactiveDialogueManager` 的
`LIGHT_FOLLOWUP` 阶段会拼一段「主人刚才问了你一个问题…」的指令发给 LLM。

旧版本把它当普通主人消息发送，并且写进了 TLM 的对话历史（`addUserHistory`）——
于是它既是 AI 上下文的一部分，也会在聊天记录里以「主人说的话」出现。

### 现在的状态

R1049/R1051 起：内部触发用随机标记 `[promaid:internal:<uuid>]` 包起来，
`MaidChatLanguageMixin` 按 system 消息发送，`MaidInternalHistoryMixin` 在
`addUserHistory` 入口取消写入。新产生的记录不会再出现提示词。

但旧存档里已经写进去的条目不会自己消失。R1087 在
`MaidInternalHistoryMixin` 里补了读档清理：`readFromTag` 返回后，按历次内部触发的
固定开头（`【系统·主动补充…】`、`【系统·事件…】`、`（你独自待了一会儿…`、
`主人刚才问了你一个问题`）删除历史条目。只按前缀认，玩家真说过的话不动。

## 三、扫帚复制审计（结论：未发现可复现路径）

物品账目只有两条：

| 场景 | 物品 | 扫帚实体 |
|---|---|---|
| 扫帚在背包 → 骑上 | 抽 1 件，记入 `DEBT`（UUID → 物品） | 新生成一把，`kill()` 时**不掉落** |
| 收工/换任务 | `DEBT` 取出 1 件还给背包 | `m_6074_()` 移除，`AbstractEntityFromItem` 覆写的 kill 不触发原版掉落 |
| 世界里的扫帚 | 不动 `DEBT` | 下鞍后原样留在世界里，玩家可拾取 |

`AbstractEntityFromItem` 只有**玩家蹲下攻击**那条 `applyHitEntityLogic → killEntity`
会掉 `getKilledStack()`；它先 `discard()` 把乘客弹下去，再掉一件。此时行为链拿不到
骑乘中的扫帚，`dismount` 直接返回，不会再从 `DEBT` 还第二件。`pickUpBroom` 是先插背包、
成功后才 `kill` 掉落物实体，也不会「捡了还留一份」。

唯一会主动多给一件的是 `MaidGiveBack.give` 在背包插入**抛异常**时的兜底
（日志写明「宁可多一件也绝不吞」）——那是异常路径，不是骑上/下来的常规流程。

`DEBT` 的 `forget(UUID)` 目前没有外部调用点：扫帚实体被外力移除时（蹲击、`/kill`）
会留下一条孤儿欠账。但欠账只在「骑着的正是这个 UUID 的扫帚」时才会兑现，孤儿条目
不会被第二把扫帚冒领，所以是内存表里的一条死记录，不是复制源。

## 改动文件

- `promaid_src/com/maidsmart/combat/MaidBroomBehavior.java`
- `promaid_src_neo/com/maidsmart/combat/MaidBroomBehavior.java`
- `promaid_src/com/maidsmart/mixin/MaidInternalHistoryMixin.java`
- `promaid_src_neo/com/maidsmart/mixin/MaidInternalHistoryMixin.java`

## 验证

- Forge / Neo 编译均 `EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`（注入点 285，含新增的 `readFromTag`）。
- 专服回归：`test_server.py 1201` PASS、`test_maid_load.py 1201` PASS、
  `test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS。
- Forge jar：18,135,634 字节，SHA256
  `8470BF784CFEF1186116EAD9D067558F696EB5CE1AD6727FA35278BA6BEFAF30`。
- Neo jar：18,195,491 字节，SHA256
  `983BBD8FF241ACD2FBE5BF523FED4CCB2E36EB67C999BAA6F0ACB1EB34E24CFC`。
- 已部署 4 实例，`match=True`。

## 边界

- 扫帚门禁只影响**无玩家驾驶**的扫帚；主人亲自开着扫帚带她时，仍按玩家的驾驶输入飞。
- 缺弹药时她留在扫帚上原地悬停，不会下鞍（下鞍会从空中掉下去）。
- 聊天记录清理只删以固定提示词开头的条目；玩家正常聊天、女仆回复都不受影响。
- 已经进入历史摘要（compressedSummary）的旧提示词无法逐条还原，不在清理范围内。
- 第三项是审计结论，没有改代码；若仍有人能复现「多一把」，需要提供：骑的是背包里
  抽出的那把还是世界里放的、下扫帚的方式（换任务/缺件/玩家蹲击）、以及是否跨维度。

## 验收

1. 主人放一把扫帚在女仆脚下、推她上去：背包里只有远程武器、没有任何弹药。
   - 她留在扫帚上原地悬停，气泡提示缺「弹药」，不再自己飞走。
   - 补上弹药后自动起飞/跟随。
2. 扫帚在她背包里、只有武器没有弹药：仍旧不取扫帚、不起飞（与上一条一致）。
3. 打开旧存档的女仆 AI 聊天记录：以「主人刚才问了你一个问题…」开头的旧提示词消失，
   玩家自己的消息与女仆回复仍在。
4. 让她骑扫帚 → 收工：背包里扫帚数量不变；世界里的扫帚下鞍后仍在原地。
