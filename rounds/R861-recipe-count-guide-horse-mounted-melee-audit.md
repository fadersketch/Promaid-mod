# 实测八百六十一 · 大运配方计数更正 + 手册骑乘列表更正 + 骑马近战罚站审计

## 需求 / 来源

开发者指令（本轮起因，三条）：

> 1. 不应该是钻石块×2吗？要不然工作台9个格子怎么摆得下？
> 2. 有玩家反馈 1.20.1 和 1.21.1 僵尸马和骷髅马是没有办法安装鞍骑乘的，手册内容有误。
> 3. 有人反馈酒狐在近战状态下骑马会有 bug：部分情况下发现敌人或者打一下敌人之后会停在原地不动，直到
>    超出跟随距离才会返回，之后重复这个过程。回复 @Observecontrol：是女仆在近战模式下骑马大部分情况下
>    会打一下之后后撤直接原地罚站，或者在视野内发现敌人时罚站，只有玩家和女仆距离超过限制才会行动，
>    然后这种情况只有在女仆一刀没砍死的情况下才会发生。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树逐字镜像、临时脚本用完即删。

## 评估

### 一、大运配方计数：应 ×2 不是 ×4（开发者对）

配方形状 `IDI / RMR / IDI`：`I`(铁块) 出现在 4 个角、`D`(钻石) 出现在**上中/下中 2 格**、`R`(红石块) 2 格、
`M`(矿车) 1 格 —— 合计 9 格。所以配方**本来就只吃 2 个 D**。上一轮（R860）把文案写成"钻石块×4"，
**沿用了原文案里早已存在的笔误**（原文"钻石×4"就与配方不符）；本轮统一改成 **钻石块×2**。配方 JSON
本来就是 2 格 = 2 个钻石块，**不用改**。

### 二、手册骑乘列表：僵尸马 / 骷髅马（开发者对，javap 已证）

`javap -c` 实证（1.21.1 `client-...-srg.jar`）：`ZombieHorse` / `SkeletonHorse` 都覆写了
`mobInteract(Player, InteractionHand)`，但**整段字节码里没有任何一处引用鞍**（不调 `equipSaddle` /
`isSaddleable`）；而 `AbstractHorse`（其父类）才是实现 `Saddleable`（`isSaddleable/equipSaddle/isSaddled`）
的那一层。也就是说这两匹马**玩家无法给它们装鞍** → 本模组的"已上鞍"绑定判据永远不成立 → 用指挥棒
绑不上、骑不了。手册把它们列进"可以骑的原版兽"是错的。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 定位配方形状与全部复刻文案；javap 实证僵尸马/骷髅马；读骑乘驱动链路 | 本文 §评估 | ☑ |
| 2 | 实现 | 两树文案：`钻石块×4` → `钻石块×2`（中）、`diamond blocks` 4 → 2（英），共 8 文件 | 8 文件 | ☑ |
| 3 | 实现 | 两树手册骑乘列表去 僵尸马/骷髅马 并加注（Java 原文 + i18n key + 英文 value） | 6 文件 | ☑ |
| 4 | 护栏/验证 | 两树编译、四护栏问题数不增、打包自检、镜像比对 | 构建输出 | ☑ |
| 5 | 审计 | 骑马近战罚站的根因评估（本轮只评估、不改行为，见 §边界） | 本文 §评估·三 | ☑ |
| 6 | 留痕 | `rounds/RELEASES.tsv` 同号重打留痕 + 更新本文 §验证 | 两处 | ☑ |
| 7 | 验收 | 交开发者实测（配方 2 个钻石块出大运；手册不再列僵尸/骷髅马） | 待开闸 | ☐ |

## 改法

1. **配方计数**（两树，8 处文案）：
   - 中：`铁块×4 + 钻石块×4 + 红石块×2 + 矿车×1` → `…钻石块×2…`；`铁块×4、钻石块×4、…` → `…钻石块×2、…`。
   - 英：`4 iron blocks + 4 diamond blocks + …` → `+ 2 diamond blocks +`；`,` 连接词版同改；
     `Iron blocks x4 + diamond blocks x4 + …` → `…diamond blocks x2…`。
   - 命中：`GuideChaptersCombat.java`、`GuideScreen.java`(javadoc)、`PromaidConfigScreen.java`、
     `lang/{zh_cn,en_us}.json` 的 `da_yun.desc4`、`i18n/en_us.json` 四条 key/value。
2. **手册骑乘列表**（两树）：`马 / 驴 / 骡 / 骷髅马 / 僵尸马 / 猪 / 炽足兽 / 骆驼…）` →
   `马 / 驴 / 骡 / 猪 / 炽足兽 / 骆驼…；僵尸马、骷髅马 Java 版装不上鞍、骑不了）`；
   英文 value 同步（`…camel…; zombie horses and skeleton horses cannot be saddled or ridden in Java Edition`）。
   命中：`GuideChaptersCombat.java` + i18n key/value。
3. **不改版本号、不写 changelog。**

## 评估·三：骑马近战"打一下/发现敌人就罚站"

**现象**：近战任务下被骑乘指挥棒绑在马上的女仆，砍一下（没砍死）或刚在视野内发现敌人后，**停在原地不动**；
只有"主人—女仆距离"超过跟随阈值时她才动一下（回到主人附近），然后重复。**没砍死才发生**。

**链路**（读 `RideBindManager` 地面坐骑驱动 + `MaidCombatTacticsBehavior` + `MaidRideKit`）：

地面原版坐骑（马/驴/骡/骆驼/猪，`kindOf==null`）的驱动目标是**她想到的点**（`driveLandMount`）：
- ① 她自己导航上的目标点（`MaidRideKit.ownNavigationTarget`：读她 `PathNavigation` 的当前路径点）——仅当
  `modeMove = hasModeMovement || fighting` 为真时取；`hasModeMovement` = 任务不是 `touhou_little_maid:idle`。
- ② 大脑 `WALK_TARGET`（剔除"指着主人那条"）。
- ④ 兜底：**跟着主人**——离主人 `> followDist` 才喂主人的位置，够近就停并转向主人。

**根因（判断）**：她骑马时是**乘客**。TLM 原版的"目标超出近战距离就走过去"那条行为对**乘客**是关的
（`EntityMaid.canBrainMoving()` 里的 `!isPassenger()`）→ 大脑不写 `WALK_TARGET`；而**本模组的单兵战术
行为**（`MaidCombatTacticsBehavior`）只在 **`isActive`** 为真时接管走位，其门槛之一是
`distance(maid, target) <= engageRange`——**近战 `MELEE_ENGAGE_RANGE = 8.0` 格**。
于是：
- 敌人在 **8 格外**（刚发现 / 后退拉开后）→ 战术不接管 → ① ② 都空 → 落 ④"跟着主人"→ **罚站**，
  直到主人离得远才动一下。
- 贴脸时战术在跑（跳劈/绕圈），砍一刀后 `navigateAway` 撤开 → 距离拉开过 8 格 → 战术退出 → 又落 ④ 罚站。
- "**一刀没砍死才发生**"也对得上：砍死了目标消失（`fighting` 变假、也不会有后续追打），
  没砍死才有"目标还活着但已在 8 格外"的僵局。

**建议修法（本轮未实施，待开发者点头）**：在地面坐骑驱动里补一档——当 `fighting` 为真、且 ① ② 都没给出
走位点时，**朝她的攻击目标走过去**（走到近战攻击距离内即停），而不是落 ④"跟着主人"。这正好补上
"乘客拿不到 TLM 那条接近行为、战术又只管 8 格内"的缺口，且**只在 ①② 都空时**生效，不与战术抢移动。
按新规矩它属于"对女仆行为的修改"，需给它一条**自己的开关**（拟 `ride.pursueTarget`，默认开、随 DIY 关），
并同步面板/手册/i18n/护栏基线。**待确认后单独一轮实施。**

## 触及文件

**改动**（两树各一份）：`com/maidsmart/guide/GuideChaptersCombat.java`、`com/maidsmart/guide/GuideScreen.java`、
`com/maidsmart/config/PromaidConfigScreen.java`、`assets/maid_smart/lang/{zh_cn,en_us}.json`、
`assets/promaid/i18n/en_us.json`。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0` |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge 17,568,915 B（`7741953c…136333`）、Neo 17,607,854 B（`747dbdab…4555d4`） |
| 护栏 | `check_workflow` FAIL 0 / WARN 3；`check_preset` FAIL 0 / WARN 2；`check_comment_style` 2262（均与基线同） |
| 改动核对 | `钻石块×2` forge=8/neo=8、`钻石块×4` **0/0**；`2 diamond blocks` 4/4、`4 diamond blocks` **0/0**；`diamond blocks x2` 1/1、`x4` **0/0**；新骑乘列表 2/2、旧列表 **0/0**、`skeleton horse / zombie horse`（英文）**0/0**；配方 `D` 仍 `minecraft:diamond_block`（未动） |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |

## 版本与上传

- 版本保持 **1.3.2**；**不**部署、**不**跑回归、**不**发 release；同号重打按 WORKFLOW §5.2 留痕。

## 验收

**未验收**。

## 边界

- 本轮只做**文案更正**（一、二两项），**不改**任何行为；第三项只给出**根因评估与建议**，
  待开发者确认后再单独一轮实施（并配真开关）。
- 配方 JSON 保持 2 个 `minecraft:diamond_block`（`IDI/RMR/IDI` 只有 2 个 D 格）。

## 台账

- 无新增结构债。TD-07（i18n 漂移）顺带受益：改动的 key/value 两树同步。
