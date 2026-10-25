# R1124 飞过去前置条件提示

## 排班

- **跑道**：C 功能修复（承接 R1123 验收反馈；无新开关、无版本号变更）。
- **开工前读过**：`PatrolNetworking.FlyToTargetPacket.handle / findEligibleMaid`、
  `MaidBroomKit`（isModeActive / missingParts / hasBroomItem / isRidingBroom）、
  `MaidFlightKit`（hasElytra / hasFlightPropellant）、
  `MaidMountCompat.outOfFuel`（载具电量闸）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 复现「点了没反应」 | 不满足飞行条件时只写目标，没有任何提示 | ☑ |
| 1 | 前置检查 | 扫帚任务/载具/鞘翅三条路，任一可飞即放行 | ☑ |
| 2 | 系统消息 | 缺什么就告诉玩家（扫帚、远程武器、弹药、鞘翅、推进剂、载具没电） | ☑ |
| 3 | 载具电量 | 复用引擎同口径的 `outOfFuel`（无能量仓的车不拦） | ☑ |
| 4 | 两树编译/14 护栏/双 jar/部署/四测试/轮次文档 | 全绿 | ☑ |
| 5 | 文档/台账 | R1124 + RELEASES | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者，见文末「验收」。

## 需求 / 来源

> 「如果于玩家点了飞过去，但是女仆不满足飞行的条件，也需要用系统消息提醒一下。
> （比如没有烟花或者鞘翅，没有扫帚。或者飞机没电。）」

## 评估

- `findEligibleMaid` 只判断"关系"（同乘扫帚 / 同乘飞行载具且她在驾驶位 / 武装拴绳），
  不看"她能不能飞"。于是缺鞘翅、缺推进剂、扫帚任务却没扫帚、载具没电时，
  客户端会发目标、服务端写下 `MaidTravelTarget`，但飞行链路起不来——玩家看到的就是"点了没反应"。
- 载具电量已有同口径判据 `MaidMountCompat.outOfFuel`（引擎 `getEnergy() <= 0` 那一闸，
  无能量仓的船/汤姆6 不拦），此前只用于驾驶层，未暴露给前置检查。

## 改动（两树 `PatrolNetworking` + `MaidMountCompat`）

- `MaidMountCompat.flightOutOfFuel(mount)`：`outOfFuel` 的公开只读包装。
- `PatrolNetworking.flyBlockedReason(maid)`：返回 null = 能飞，否则是给玩家的原因。
  - 她骑着扫帚：任务不是扫帚模式 → 「她的任务不是扫帚模式，扫帚不会起飞」。
  - 她骑着飞行载具：`flightOutOfFuel` → 「载具没电了，先给它充电」。
  - 她骑着其他载具 → 「她正被载具载着，先让她下车再飞」。
  - 没有载具：扫帚路线（`isModeActive`）或鞘翅路线（`hasElytra && hasFlightPropellant`）
    任一成立即放行；都不成立时汇总缺失项：扫帚任务用 `MaidBroomKit.missingParts`
    （扫帚 / 远程武器 / 弹药）+ 可用鞘翅 + 烟花火箭/孔雀羽扇/位移法术。
  - 探测异常不拦（交回飞行链路自己报），避免把能飞的误判成不能飞。
- `FlyToTargetPacket.handle`：找到女仆后先跑前置检查；不通过就
  `[飞过去] … 拒绝起飞：原因` 落日志 + 给玩家
  `§c<女仆名> 不能飞过去：<原因>`，然后返回（不写目标）。

## 验证

- 两树编译 `EXITCODE=0`。
- 14 项护栏 `FAIL 0 / WARN 0`。
- 打包 + 部署 4 实例 `match=True`。
- 四套专服回归 PASS：`test_server 1201`、`test_server neoforge1211`、
  `test_maid_load 1201`、`test_maid_load neoforge1211`。
- Forge jar：18,190,973 字节，SHA256
  `642F0ABAAD8B6CAFAFBC1FC09C0DB0194245EF25388D43E2475505E22369332F`。
- Neo jar：18,252,157 字节，SHA256
  `84C603CC8E5D51E58DC42E7FF65959E681A46DFDFDD526B0B23A5752AB6D21F8`。
- **反证**：
  - 放行条件与飞行链路自己的入口一致（扫帚 `isModeActive`、鞘翅 `hasElytra` +
    `hasFlightPropellant`、载具 `outOfFuel`），不存在"提示能飞但链路不启动"的分叉。
  - 不通过时直接 return，目标不会写进 `MaidTravelTarget`，不会留下"永远飞不过去"的残留任务。

## 边界

- 只做前置提示，不改变 `findEligibleMaid` 的"谁可以执行"判定。
- 没有能量仓的载具（船 / 汤姆6）按"不缺电"放行，与引擎口径一致。
- 扫帚的远程武器/弹药要求沿用 `MaidBroomKit.missingParts`，与扫帚模式起飞判据同源。
- 版本号仍为 **1.3.3**，同号重打；发布素材等验收后再写。

## 验收

1. 绑着女仆点「飞过去」但背包没有鞘翅/烟花：聊天栏应出现
   `§c<女仆名> 不能飞过去：缺少 可用鞘翅、烟花火箭/孔雀羽扇/位移法术`，日志有
   `[飞过去] … 拒绝起飞：…`，且她不会起飞。
2. 扫帚任务但没扫帚：提示里应出现「扫帚」（有远程武器/弹药问题时一起列出）。
3. 坐在没电的直升机驾驶位点「飞过去」：提示「载具没电了，先给它充电」。
4. 条件齐备时仍按原流程出现「开始飞往目标点」，不新增任何拦截。
