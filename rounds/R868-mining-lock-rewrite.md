# 实测八百六十八 · 挖矿目标锁定/移动重写（借鉴 maid_mining，保留三大卖点）

## 需求 / 来源

开发者实机反馈（同一轮第二条）：

> 女仆挖矿很不好用。当前的逻辑在伐木里面还行，但是对挖矿就是很差。
> 挖矿的对矿物的锁定等各方面都需要进行重写。当前问题就在于女仆对于矿物的锁定能力很差，
> 开隔墙穿透也一样。而且很容易站在原地不动。有看门狗也没什么用。
> 树木因为是一棵树，所以容错率比较高，但是挖矿必须要进行重写了。这是我弄到了一个新的
> （`maid_mining_addon-forge-1.4.1`）。我觉得寻路和判定方面的逻辑可以学习一下他。
> 我们这边的招牌卖点：第 1 个是可以搭方块过去，第 2 个是可以自行调整障碍物和目标，
> 第 3 个是会自己切换镐子。**保留这 3 点，剩下的可以搬它的。**

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树逐字镜像、临时脚本用完即删。

## 评估

**跑道**：A 修 bug（P1，功能明显失效：挖矿目标抖动 / 站桩 / 看门狗无效）。

### 参考实现（反编译 `maid_mining` 1.4.1，`MaidAnchorMoveBehavior` 638 行）的关键口径

- **目标锁 = 固定锁 + 超时**：`TargetLock{target, sinceTick}`；**不周期性重挑**，只在①目标失效、
  ②`elapsed >= targetTimeoutTicks` 时释放。`targetTimeoutTicks = min(max(ceil(3/destroyProgressDelta),100),1200)`
  ——**按"挖完这块要多久"放大**，硬矿给足时间，不误弃。
- **`ABANDONED` 集合**：本锚点内暂时放弃的矿，**只在换锚点（relocate）时清空**；防止"选中→弃→再选"打转。
- **搜索**：锚点立方体；排序按**到女仆当前位置**的欧氏距离²（不是锚点）；相位先上（y≥脚）后下。
- **够不着**：`Yield`（绕到侧面/上岸）→ 走不到就弃；`Migration`（朝主人游走）→ 换锚点。
- **看门狗**是"锁超时"而不是"位置不动"：卡住 30t 结束迁移、越界 200t 重定位锚点。

### 我们当前的病（两个子代理逐行核过 `MaidMineBehavior` 2923 行）

1. **每 5 秒重挑 + 重置超时**（907–916）：`destroyProgress<=0` 时每 100 tick `findOre` 重挑；
   两块矿分数接近 → A→B→A 抖动，每次都给新的 15 秒，**够不着的矿永远等不到弃置**。
2. **`reachBonus(-400)`**（2695）：随女仆位置实时翻转"谁进伸手圈谁最优"，分数跳变 400 → 锁定更不稳。
3. **价值权重压过距离**：`value(300)×weight(2)=600` 的减分，远处钻石矿压过眼前煤矿 → 选中够不着的。
4. **弃置被看门狗免疫**：三处弃置都 `markProgress` 喂看门狗 → "选→弃→选"长舞蹈永不触发看门狗。
5. **看门狗保留死目标**：`hardResetStuck` 刻意保留 `targetPos` 与超时时钟 → 卡死根因就是"锁了够不着的矿"，
   重置只清计数器、目标照旧 = "有看门狗也没什么用"。
6. **`findStandNearOre` 返回 null 就不给移动目标**（1501–1507）：矿被 6 方向包住时她本 tick 一步不走，
   只能指望 blocker 分支恰好命中 → 最典型的"站着不动"。
7. **斜坡门槛 4.5**：4.5~8 格段"不垫也不走"（伐木是 8.0，所以伐木更少站桩）。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 反编译 `maid_mining` 1.4.1（CFR）并产出架构报告（锁定/超时/Yield/Migration/连锁/选镐） | 报告（本会话） | ☑ |
| 2 | 侦察 | 逐行核我们 `MaidMineBehavior` 的 tick/锁定/移动/看门狗，定位 7 条病根（上 §评估） | 报告（本会话） | ☑ |
| 3 | 设计 | 定重写范围：**只改"锁定 + 移动兜底 + 看门狗"三块**；三大卖点（搭方块/调障碍/换镐）与连锁挖矿**原样保留** | 本文 §改法 | ☑ |
| 4 | 实现 | 删「每 5 秒重挑」块；目标只在失效/超时/不可达时释放 | 两树 | ☑ |
| 5 | 实现 | 锁定超时 `targetTimeoutTicks`（按破坏速度 3× 放大，下限=配置、上限 1200） | 两树 | ☑ |
| 6 | 实现 | 新增 `ABANDONED`（本锚点排除表，换锚点才清）；三处弃置改走 `abandon()`；扫描层跳过 | 两树 | ☑ |
| 7 | 实现 | 排序基准改"到女仆距离²"、删除 `reachBonus` | 两树 | ☑ |
| 8 | 实现 | 移动兜底：`findStandNearOre` 扩 ±2 外圈 + 最后兜底直接朝矿走（**永不返回 null 不给目标**）；斜坡门槛 4.5→8.0 | 两树 | ☑ |
| 9 | 实现 | 看门狗：`hardResetStuck` **不再保留死目标**（并入本锚点排除表，重新评估） | 两树 | ☑ |
| 10 | 实现 | 清理：`ABANDONED` 接入 `forget`/`purgeStaleMaids`/`relocate`/`slideAnchor` | 两树 | ☑ |
| 11 | 镜像 | 逐文件核对 SRG↔Mojang（除 API 名外逐字节一致） | 引用计数比对 | ☑ |
| 12 | 验证 | 两树 `javac EXITCODE=0`；四护栏问题数不增；打包自检 | 构建输出 | ☑ |
| 13 | 留痕 | `rounds/RELEASES.tsv` 同号重打留痕 + 本文 §验证/§验收 | 两处 | ☑ |
| 14 | 验收 | 交开发者实测（盯住一块矿不再乱换；不再站桩；真挖不动的矿不会让她空转） | 待开闸 | ☐ |

## 改法

**总闸**：本模组不新增开关——这是**挖矿任务自身的内部重写**（不是对 TLM 行为的介入）；挖矿本身仍由玩家
在 TLM 任务表里选择，其内部增强项（连锁/搭方块/穿透预算/危险规避…）开关不变。与实测八百四十一 / 八百四十三
（同样改矿救/矿锁定而没加开关）同口径。

1. **删「每 5 秒重挑」**（旧 903–916）：锁上的目标一直保留，直到①它不再是矿/开路石、②锁定超时、
   ③判不可达。顺带删掉只服务它的 `MINE_REPICK_INTERVAL` 与 `repickCooldown` 字段。
2. **`targetTimeoutTicks(maid, pos)`**：`max(mine.targetTimeout, min(ceil(3/δ), 1200))`，
   δ 复用挖掘公式（digSpeed/hardness/(correct?30:100)×speedFactor，封顶 0.25）。硬矿不再被 15 秒误弃。
3. **`ABANDONED`（`Map<Integer,Set<BlockPos>>`）**：弃置（超时 / 视线被挡 / 走路卡死 3 次 / 看门狗重置）
   统一走新 `abandon(maid, pos, gameTime)`——同时写 30 秒短排 `RECENT_DISCARD` 与本锚点排除表；
   `fullScanOres`/`pickFromCache` 跳过；`relocate`/`slideAnchor` 清空（换框了重新看）。
4. **排序**：`score = distSqToBlock(maid, p) + depthPenalty*max(0,feetY-p.y) - value*valueWeight`；
   **删 `reachBonus`**（不再有 -400 的实时翻转）。
5. **移动兜底**：`findStandNearOre` 候选加 ±2 外圈 4 个 + 斜角 4 个；仍找不到 → **返回矿自己**当移动目标
   （导航尽量贴近，blocker 分支接管），**绝不返回 null**；`approachOre` 斜坡门槛 `4.5 → 8.0`。
6. **看门狗**：`hardResetStuck` 不再保留 `targetPos`/`TARGET_SINCE`，改为把旧目标并入 `ABANDONED` + 短排，
   然后 `targetPos = null`（下一 tick 重新评估另一块）。保留 `RECENT_DISCARD`（学到的不可达知识）。
7. **保留不动**：`pillarUpStep`/`slopeStep`/`descendStep`/`bridgeToOre`/`pillarGuard`/`takeBuildBlock`（卖点 1
   搭方块过去）、`findBlockingBlock`→切目标挖穿（卖点 2 自行调整障碍物和目标）、`MaidToolAutoEquip.ensureForTarget`
   （卖点 3 换镐）、`chainQueue`/`refillChainQueue`/`chainBreakAll`/`refillObstacleChainQueue`（连锁）、
   `countBlocking`/穿透预算、`isDangerAt` 危险规避、`WorkAreaClamp` 工作圈、`expirePlaced` 搭块清理。

### 不做（本轮明确不搬）

- 不搬参考的 **Yield / Migration / 相位先上后下 / 锚点=固定立方体中心**（我们的锚点滑动 `slideAnchor`
  已有等价"换区域"，且改动面太大；本轮先修"锁定 + 站桩 + 看门狗"三条最痛）。
- 不搬它的 per-maid NBT 配置 GUI / 客户端锚点渲染（调试用，非卖点）。
- 不改 `MaidMineTask` 的注册（行为优先级 5、`enableLookAndRandomWalk=false` 不变）。

## 触及文件

**改动**（两树各一份）：`com/maidsmart/task/MaidMineBehavior.java`（唯一）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0`（Forge 88 警告，与上轮同档；Neo 同档） |
| 护栏 | `check_preset` FAIL 0 / WARN 2（配置项 600/604、DIY 171/175，**无新增开关故基线不变**）；`check_workflow` FAIL 0 / WARN 3（三条旧 WARN）；`check_comment_style` 927 文件 / **2262 处**（与基线同数；期间新增了一条 QUOTE-SPEECH——`原话` 触发，已改写）；`check_panel_coverage` FAIL 0 / WARN 0 |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge 17,577,047 B（`121989fb3f85…046fe5`）、Neo 17,615,972 B（`b5a24bad20e0…024693`）；桌面 `Promaid_本体当前` 快照刷新一致 |
| 镜像 | 逐符号计数两树一致：`ABANDONED` 13、`targetTimeoutTicks` 3、`this.abandon(` 3、`distSqToBlock(maid, p)` 2、`hDist <= 8.0` 1；已删符号两树均为 0：`MINE_REPICK_INTERVAL` / `repickCooldown`（`reachBonus` 仅剩 2 处“去掉 reachBonus”注释） |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |

**反证**：改前——锁定一块矿后每 5 秒重挑（`destroyProgress<=0`）、目标抖动且换目标即重置 15 秒超时；`findStandNearOre` 无立足点返回 null 时 `approachOre` 本 tick 不设移动目标 → 站桩。改后——锁定到失效/超时（按破坏速度放大）才释放，弃置进本锚点排除表不再回头；
`findStandNearOre` 兜底返回矿本身，必定给出移动目标。
开发者实测可看的日志关键词：`mine stuck-reset`（看门狗重置，重置后**会换目标**而不是原目标），以及观感上"盯住一块矿不再乱换、不再长时间原地不动"。

## 版本与上传

- 版本保持 **1.3.2**；**不**部署、**不**跑回归、**不**发 release；同号重打按 WORKFLOW §5.2 留痕。

## 验收

**未验收**。

## 边界

- 只动挖矿；伐木（`MaidWoodBehavior`）本轮不动（开发者说伐木"还行"；仅 R867 给它补了传送豁免）。
- 参考实现里"锚点固定 + Yield 绕行"没搬，我们仍用"锚点滑动迁移"——框内无矿时锚点朝主人方向滑一环宽。
- `ABANDONED` 只在换锚点/重埋时清；同一框内被排除的矿在框没换之前不再回头（这是有意的：反复啃同一块
  够不着的矿正是"锁定能力差"的表现）。
- `RECENT_DISCARD` 仍在挖掉任一块后清（短排本意：地形变了可能又能挖了）。

## 台账

- **BUG_DEBT**：登记挖矿目标锁定/站桩（实测八百六十八）并本轮清偿。
- 无新增配置项、无新 i18n key、无新 mixin → 护栏基线不变（`check_preset` 仍 171/175）。
