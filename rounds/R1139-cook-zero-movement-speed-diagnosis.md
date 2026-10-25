# R1139 烧制"失去寻路能力"的实因：女仆移速被第三方事件写成 0 + 链路日志降噪

## 排班

- **跑道**：A 玩法修复（玩家实测："问题未能解决，女仆失去了巡路能力"）。
- **范围**：`promaid_src` 与 `promaid_src_neo` 同步改；版本号仍 **1.3.3**（同号重打）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 用实机日志确认"有路但不动" | `stand:walk ... 距离=3.62 navDone=false`，位置与 Motion 水平分量恒 0 | ☑ |
| 2 | 找到真正卡点 | 存档 NBT：该女仆 `minecraft:generic.movement_speed` 的 **base = 0.0** | ☑ |
| 3 | 走路前做零移速自检与自愈 | `cook speed-fix:` 日志 + base 恢复 0.7，随后能走 | ☑ |
| 4 | 站位超时改为"看进展"而不是"看秒数" | 有靠近就清零；10 秒一步没靠近才换台 | ☑ |
| 5 | 修好链路日志的节流 | 每个 step 各自 5 秒一条，不再被交替步骤击穿 | ☑ |
| 6 | 恢复站立改为按当前姿势清 | 不再带着 `Pose.SITTING` 走路 | ☑ |
| 7 | 编译 / 14 护栏 / 双 jar / 部署 / 四套专服测试 / 文档 | 全绿 | ☑ |

## 日志与存档实证（1.21.1 NeoForge）

```
22:35:44.790  cook chain: step=stand:walk 站位=(-160,-60,793) 距离=3.62 navDone=false
22:35:44.837  cook chain: step=check:ok 台=(-160,-60,794)
（以上两条每 tick 交替，持续约 2 秒）
22:35:46.592  cook stand-giveup: ... 站位=(-160,-60,793) 距台 4.45 格 → 收成品换台
22:35:47.638  cook chain: step=scan:none 扫描半径 16 内没有可用的台
```

存档（`saves/新的世界 (2)/entities/r.-1.1.mca`，22:35:55 写入）该女仆实体：

```
Pos    = [-161.66, -60.0, 790.64]     ← 与 22:35:44 完全一致，整段时间一步没动
Motion = [0.0, -0.0784, 0.0]          ← 只有重力，水平速度恒 0
Sitting = 0；maid_smart_work_still = 0；maid_smart_station_lock = 0
attributes: id=minecraft:generic.movement_speed, base=0.0   ← 关键
```

对比 `saves/新的世界` 里同版本女仆：`base=0.7`（原版默认值）。所以不是"寻路算不出路"，
是**她的腿被设成了 0 速**：`MaidMoveControl.tick()` 算出 `speedModifier × MOVEMENT_SPEED`
= `1.0 × 0.0` → `Mob.setSpeed(0)` → `zza=0` → `travel` 一步都不产生位移。
`navDone=false`（路径确实存在）与"位置恒定"这两个事实同时成立，只有这个解释能同时满足。

## 根因

移速 base 被写成 0 的来源不是 TLM（`FavorabilityManager` 只写攻击/生命；TLM 的
`createAttributes` 根本不写 MOVEMENT_SPEED），也不是本模组（全树无 `setBaseValue`）。
本整合包里 `maid_cute_activity`（酒狐萝贝）的**梳尾巴/沙子**事件会临时把基础移速置 0 再还原；
在某些流程里还原没有执行 → 永久 0。证据是它自己生态里的两份文档：

- `config/maid_cute_activity-common.toml` 的 `tailBrushSandSpeedMultiplier`（默认 0.55）；
- 已停用的 `maid_cute_activity_patch` 配置原文：
  「maid_cute_activity 的一些事件会把女仆基础移速临时置 0 再恢复；特定操作会导致恢复没有执行，
  女仆移速永久为 0 走不动路」，并给出紧急修复项 `sugarResetMaidSpeed = true`（用糖右键女仆）。

也就是说：**这一轮无论怎么改寻路都不可能走**，因为问题在移动速度属性，不在导航。

## 改动

### 1. 走路前自检 + 窄口径自愈（两树）

新增 `ensureMovable(maid, gameTime)`，在站位走动前调用：

- 有主 + 本模组干活任务 + 有效移速 ≤ 0.001 时才动作；
- base ≤ 0（= 上述第三方 bug 的形态）→ 把 base 恢复成原版默认 **0.7**，并记：
  `cook speed-fix: maid=<名> 移速 base=0.00 被置 0（有效值 0.00）→ 走路必然原地不动，不是寻路问题；已恢复 0.7。来源通常是 maid_cute_activity 的梳尾巴事件，可启用 maid_cute_activity_patch 或用糖右键她`
- base 正常、只是被别的修改器压到 0 → **不动别人的修改器**，只记
  `cook speed-zero: ... 请检查饰品/药水`；
- 两条日志都按 10 秒限频。

### 2. 站位超时改为"看进展"

`standStallTicks` 换成 `lastStandDistSq` + `standNoProgressTicks`：

- 距离比上一次**近 0.2 格以上** → 计数清零（走得慢不会被误杀）；
- 连续 200 tick（10 秒）一步没靠近 → `cook stand-giveup`（日志里带当前移速），
  收成品、该台 30 秒冷却、解绑换台；
- 行为重启、新台绑定、放弃台、到站都同步清零。

### 3. 链路日志降噪

`trace` 从"一对 `(step|detail)` 节流"改成**按 step 各自限频**（首次出现立刻记，之后 5 秒一条）。
旧版因为 `check:ok` / `stand:walk` 每 tick 交替，键每次都在变，节流形同虚设——22:35:44 起
两秒刷了几百行。现在同一 step 两秒最多一条。

`stand:walk` 的细节字段也补全了：`距离`、`moveTo=<是否找到路>`、`移速=<当前有效值>`、`navDone`。
`moveTo=false` = 没路；`moveTo=true` 但距离不变 = 有路但走不动（多为移速 0）；两者一眼可分。

### 4. 恢复站立

`standUp` 旧版只在 `isMaidInSittingPose()`（TLM 的坐姿标志）为真时改姿势；但烧制行为自己只改过
`Pose.SITTING`、没碰标志，所以旧判断永远不成立——她会带着坐姿去走路。现在按当前姿势清：
`getPose() == SITTING → setPose(STANDING)`。

## 验证

- 两树编译：`EXITCODE=0`。
- 14 项护栏：全部 `FAIL 0 / WARN 0`；`check_comment_style` 为 `PASS`；297 个 mixin 注入点。
- 打包：
  - `patched/promaid-1.3.3-forge-1.20.1.jar`，**18,212,346 字节**，
    SHA256 `7DE5FFD7DAA843FC2B4E756825258727FC2D5198ACA19E08D442654111C152C2`。
  - `patched/promaid-1.3.3-neoforge-1.21.1.jar`，**18,273,332 字节**，
    SHA256 `2B0CD3171984B7408353CD56DB8192E4A231164853D31437AA774CBD30BA6A45`。
- 部署：4 个实例全部 `match=True`。
- 专服测试：`test_server` / `test_maid_load` × `1201` / `neoforge1211` 全部 `verdict: PASS`。

## 边界

- 自愈只碰"base 被写成 0"这一种形态，且只对有主女仆、只在干活任务里；战斗/空闲/骑乘不动属性。
- base 正常但有效值被负向修改器压到 0 时**不擅自改**，只指名道姓地记一条，交由玩家处理。
- 恢复值取原版默认 0.7（与酒狐萝贝补丁的 `sugarResetMaidSpeed` 同口径）；若整合包本来给女仆配了
  别的基础移速，恢复后玩家会看到 0.7 —— 但 0 速状态下她本来什么也干不了，先恢复可用是优先项。
- 第三方补丁 `maid_cute_activity_patch` 目前是 `.jar.disabled`；启用后可用糖右键女仆手动复位，
  与本自愈互为兜底。

## 验收

1. 本存档里再放一台 4~6 格外的炉子：应看到一条
   `cook speed-fix: ... 已恢复 0.7`，随后 `stand:walk` 的距离开始变小、`stand:ok`、开工。
2. 正常的女仆（base=0.7）：行为与 R1138 完全一致，不会出现 `speed-fix` 日志。
3. 日志量：同一 step 两秒最多一条；`check:ok` / `stand:walk` 不再刷屏。
4. 真走不到（围死/无路）：`stand:walk` 的 `moveTo=false` 或 10 秒无进展 → `stand:giveup` 换台。
