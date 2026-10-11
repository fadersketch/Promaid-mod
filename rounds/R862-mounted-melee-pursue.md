# 实测八百六十二 · 骑马近战"打一下就罚站"：追击目标档 + 真开关

## 需求 / 来源

开发者指令（承接 R861 的评估，开发者回「是」＝按建议实施）：

> 女仆在近战模式下骑马大部分情况下会打一下之后后撤直接原地罚站，或者在视野内发现敌人时罚站，
> 只有玩家和女仆距离超过限制才会行动，然后这种情况只有在女仆一刀没砍死的情况下才会发生。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树逐字镜像、临时脚本用完即删。

## 评估（R861 已定，此处复述结论）

地面原版坐骑（马/驴/骡/骆驼/猪）的驱动目标是"她想到的点"（她自己导航的终点 → 兜底"跟着主人"）。
她骑马时是**乘客**，于是：TLM 原版"目标超出近战距离就走过去"那条对乘客是关的（不写 `WALK_TARGET`）；
本模组单兵战术只在 **`distance ≤ 8 格`** 时接管走位。**8 格外**（刚发现 / 砍一刀后 `navigateAway` 撤开）
→ 无人写走位 → 落"跟着主人"档 → **原地罚站**，直到主人离得远才动一下。"一刀没砍死才发生"因此成立
（砍死了目标消失、没后续）。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 设计 | 定修法与开关：`ride.pursueTarget`（默认开、随 DIY 关）；只在"她有活目标、①②都没给走位点"时生效 | 本文 §改法 | ☑ |
| 2 | 实现 | 两树 `MaidSmartConfig`：+1 声明 +1 定义（ride 组） | 两树 | ☑ |
| 3 | 实现 | 两树 `RideBindManager.driveLandMount`：接敌空档档改为 `pursueTarget(maid, mount)`；新增 `pursueTarget` 辅助（朝攻击目标喂导航，进近身距离即停） | 两树 | ☑ |
| 4 | 实现 | 两树 lang：+1 配置翻译 key（`config.promaid.ride.pursueTarget`） | 4 文件 | ☑ |
| 5 | 护栏 | `check_preset`：基线 170/174 → **171/175**、`GATES_ELSEWHERE` +1；重生成 `ARCHITECTURE-SWITCHES.md` | 2 文件 | ☑ |
| 6 | 验证 | 两树编译、四护栏问题数不增、打包自检、镜像/符号比对 | 构建输出 | ☑ |
| 7 | 留痕 | `rounds/RELEASES.tsv` 同号重打留痕 + 更新本文 §验证 | 两处 | ☑ |
| 8 | 验收 | 交开发者实测（骑马近战：目标在 8 格外时坐骑会追过去，不再罚站；开关可单独关） | 待开闸 | ☐ |

## 改法

1. **新开关** `ride.pursueTarget`（字段 `COMBAT_RIDE_PURSUE_TARGET`，ride 组，默认 `true`、不进 `KEEP` → 随 DIY 关）：
   「骑马追击目标（默认开）：她骑着原版坐骑接敌、而目标超出战术接战范围（8 格）时，由坐骑朝她的攻击目标走过去，
   进到近身距离即停。关掉 = 回旧行为（超出接敌范围就原地等，直到主人离得远——反馈的「打一下就罚站」）」。
2. **代码**（`RideBindManager.driveLandMount` 的"接敌但寻路为空"那一档）：原来直接 `return`（什么都不做）→ 改为
   调 `pursueTarget(maid, mount)`：开关开、目标是活的非友方、且坐骑离它还超过 `RIDE_PURSUE_STOP = 2.5` 格时，
   把**目标的位置**喂进坐骑自己的 `feedNavigation`（进到 2.5 格内则 `stopNavigation`，交给她的攻击/战术）。
   **只在 ①② 都没给出走位点时**才走这条——不与战术抢移动；进到 8 格内战术自行接管。
3. 护栏基线 +1；开关清单重生成；不改版本号、不写 changelog。

### 不做

- 不动飞行载具/龙的接敌那套（`kindOf != null`，另有链路）；不给"跟随时跟着主人"那一档动刀。

## 触及文件

**改动**（两树各一份）：`com/maidsmart/config/MaidSmartConfig.java`、`com/maidsmart/combat/RideBindManager.java`、
`assets/maid_smart/lang/{zh_cn,en_us}.json`。**工具**：`tools/verify/check_preset.py`、`ARCHITECTURE-SWITCHES.md`（生成物）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0` |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge 17,569,447 B（`ee546fe8…b48302`）、Neo 17,608,408 B（`a21b4976…b2309e`） |
| 护栏 | `check_preset` FAIL 0 / WARN 2（DIY 关闭条数 171/175，与新基线一致）；`check_workflow` FAIL 0 / WARN 3；`check_comment_style` 2262（均与基线同） |
| 镜像/符号 | `COMBAT_RIDE_PURSUE_TARGET` forge=3/neo=3、`pursueTarget(maid, mount)` 1/1、`RIDE_PURSUE_STOP` 2/2、`config.promaid.ride.pursueTarget` 3/3；两个 jar 的 `RideBindManager.class` 含 `pursueTarget`、`MaidSmartConfig.class` 含该字段 |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |

## 版本与上传

- 版本保持 **1.3.2**；**不**部署、**不**跑回归、**不**发 release；同号重打按 WORKFLOW §5.2 留痕。

## 验收

**未验收**。

## 边界

- 修的是"骑马近战追不上去"的缺口；追击到 8 格内即交回战术行为；不与"跟着主人"档冲突。
- 关掉 `ride.pursueTarget` = 完全回旧行为（含反馈的现象），便于对照。

## 台账

- 无新增结构债。TD-11 相关：新开关默认开、随 DIY 关，已纳入 `check_preset` 基线。
