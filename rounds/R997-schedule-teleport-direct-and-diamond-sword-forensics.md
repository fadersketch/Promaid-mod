# R997 排班表传送去距离限制 + 直达坐标（另附钻石剑"被吞"取证）

> 上一轮：`rounds/R996-extra-container-ammo-bridge.md`（精妙背包弓/枪弹药桥，1.3.3 同号重打，**未发布**；
> 其当次部署被运行中的游戏挡住，字节随本轮一并部署）。
> 开发者原话：「1.简化代码与更改：排班表中的传送功能不再有距离限制（也就是说距离再近都会传送），并且传送
> 的位置是直接到目标的坐标，即，使用以前集合和传她过来的时候，是直接让她/tp到主人的坐标，主人同理。
> 2.在刚刚运行的游戏里，我把女仆调整到弓模式，并给了她一把弓，但好像把我的一把钻石剑吞掉了。」

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 改动（排班表三键传送：去距离限制 + 落点直达坐标）+ B 答复（钻石剑"被吞"取证——
  日志轨迹 + 存档搜索 + 全路径 javap，当着证据说结论）。
- **开工前读过**：
  - `rounds/RELEASES.tsv`：1.3.3 两条 `workspace`（R996，**第一百三十二次**）→ 本轮按既有口径
    **同号重打**（**第一百三十三次**，前一次字节作废）。
  - `ARCHITECTURE-SWITCHES.md`：传送三键**没有**距离/落点开关（旧口径写死在代码里）→ 本轮是开发者
    点名的语义更改，**不新增开关**（与实测九百二十五"守家恢复钉死"同一先例：玩家点名的口径直接改）。
  - `DEBT.md`：BD-31 `已清@实测一千〇三`（上轮）；「跟随 / 召唤 / 传送」无未清 P0/P1。
  - 上一轮记录 R996：1.3.3 未发布、本轮顺延 R997。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位（三键全链路 + 距离闸 + 落点口径；钻石剑取证） | 每条结论指着 file:line / javap / 日志 / 存档字节（见「评估」） | ☑ |
| 1 | 两树改 + 编译 | 去 5 格闸；人工六调用点改直达；扫帚/坐骑落点＝主人所在格；清场抽成唯一一份；两树 `javac` `EXITCODE=0` | ☑ |
| 2 | 14 护栏 | `FAIL 0 / WARN 0` | ☑ |
| 3 | 同号重打 1.3.3 + 装 4 实例 | 双 jar；`match=True` ×4；`RELEASES.tsv` 两条新行（void 旧字节） | ☑ |
| 4 | 专服回归三件 | `test_server.py 1201` / `test_server.py neoforge1211` / `test_maid_load.py neoforge1211` 全 PASS | ☑ |
| 5 | 交开发者验收 | 实机复看三键手感（清单见下） | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。清单：
  1. 站她贴脸（<5 格、甚至重叠位）点「⚑ 一键集合」→ 她**确实被 tp 到你脚下**（以前静默跳过、什么都不发生）。
  2. 点「⚑ 召她过来」→ 她落在**你的坐标**（跨维度同）；骑扫帚/坐骑的连载具落在你所在格。
  3. 点「⚑ 去她身边」→ 你落在**她的坐标**（跨维度同；她飞在海面/虚空上时你也到空中——/tp 语义）。
  4. 回归对照（应一字不变）：守家（home）女仆**不动**；坐着/普通载具乘客「一键集合」保持原位；
     「召她过来」仍是"只拽人不拽载具"；自保救援 / 守家牵引等自动传送仍走"找可站立格"的老口径。
  5. 钻石剑：本轮结论"追不到确切一步"（见「评估」）——若想继续追，请给"给弓那一下"的操作回放/时间点。

## 需求 / 来源

- 开发者实机两条（原文见上）：①传送简化（去距离限制 + 直达坐标）；②钻石剑疑似被吞。

## 评估

### 传送（改前 → 改后，`file:line` 均为改后行号）

| 键 | 改前 | 改后 |
|---|---|---|
| ⚑ 一键集合（列表页） | 在场循环里有一道距离闸 `lvl == player…&& md.m_20238_(player.m_20182_()) < 5.0 → continue`（`MaidChunkLoadManager.java:979` 原址）；落点＝`standSpot → findStand`（主人身边 16 格内向下找可站立格，找不到才退主人所在格），经 `teleportCore(…, true)` | 闸**删**（距离再近也照传，`summonAll` 循环直落 `summonMaidTo`）；落点＝主人坐标本身（`teleportDirect`，`:1864`） |
| ⚑ 召她过来（详情页） | `summonOne` / `summonNamed` / `summonMaidTo` 共六处 `teleportCore(maid, owner, true)`（`:1374/:1382/:1424/:1428/:1439/:1485`） | 六处全换 `teleportDirect`——她 /tp 到主人坐标；连带扫帚 / 坐骑两档的**载具落点**也变 |
| ⚑ 去她身边（详情页） | 跨维 / 同维两分支各自 `findStandNear(…, maid.m_20183_())` 找她脚下"安全落点"（`SchedulePacketsMaid.java:396-421` 原址） | 两分支**直达** `maid.m_20185_()/m_20186_()/m_20189_()`（`:400-413`）——你 /tp 到她的坐标 |
| 骑扫帚 / 坐骑"连人带载具" | 落点 `standSpot(dest, owner, true)`（`:1561/:1698` 原址，findStand 口径） | 落点 `ownerBlock(owner)`（`:1561/:1699`）＝主人所在格；格心 +0.5 由 `teleportCoreTo` 包装保持 |

- 实现要点：清场（摔落清零 / 停导航 / 清速度 / 末影人音效）+ 到达校验（>64 格判没传到位）抽成
  **唯一一份** `teleportToExact`（`:1880`）；`teleportCoreTo` 变三行包装（`:1854-1857`，格心落点给
  扫帚 / 坐骑四步搬运）；新增 `teleportDirect`（`:1864`，精确坐标）；`teleportCore` 保留给自动路径
  （javadoc 同步：`force=true` 现由"她正在空中"的 `recallFromFlight` 使用）。
- **没动的**：home（守家）唯一豁免、坐 / 骑一键集合保持原位、「召她过来」只拽人（坐 / 普通载具先
  `stopRiding`）——豁免口径一字未改，只改"距离"与"落点"。
- 自动路径（`followIfCrossDimension` force=false、`recallFromFlight` / `recallFromFlightTo`、
  `recallBroomRiderTo` / `recallRideRiderTo`、`trySameDimPull`、`MaidHeatEscape`、
  `SelfPreservationBehavior`、`MasterDeathTeleportHandler`）**一字未动**：仍走 findStand
  "宁可她走路归队也不冒半空坠落风险"的老口径。

### 钻石剑取证（结论：追不到确切一步；弓模式 / 给弓机制不在证据链里）

- **日志轨迹**（`龙之冒险：新征程v2.4a` 的 `promaid.log`）：钻石剑 20:22 在她背包 slot 7、21:44 在
  slot 13（两次背包清点都在）；22:31:45 它在**她主手**上、同秒她被击杀（当时她在搭桥）；自动复活同秒
  完成（新实体 32813）；5 秒后进魂符、22:32:32 重召——**此后全日志再无该剑任何一条**。
- **路径逐条 javap（两版 TLM jar + 本模组源码）**：死亡走 `EntityMaid.m_6667_` → `m_5907_`
  `dropCustomDeathLoot`——墓碑（`EntityTombstone` + `CombinedInvWrapper(护甲, 双手, maidInv, 饰品,
  隐藏栏, 任务栏)` 逐格搬入）＋ `ItemFilm.maidToFilm`；我们的 `MaidAutoResurrect` 在 `MaidDeathEvent`
  里做全量 `saveWithoutId` 快照，复活＝新建实体 + `load(NBT)`；魂符 `ItemSmartSlab.storeMaidData`
  ＝全量 `saveWithoutId`；任务装备 `TaskEquipUtil.tryEquipFromBackpack` 与 `MaidToolAutoEquip.equip`
  都是干净 1:1 换手——**没有一条路径按设计会吞掉一件主手物**。
- **同期对照（证明路径本身保物）**：下界合金剑连着死了 3 次（23:11:31 / 23:11:38 / 23:12:05），每次
  复活后仍在主手；178 支火把的满背包撑过约 20 次魂符存/放往返（22:30–23:14）一件不少。
- **存档搜索**：玩家 `playerdata` 33 件物品里**没有任何剑**（其中 slot 2 是 `smart_slab_empty`）、
  末影箱无；区域文件（`.mca`）全扫只找到 `netherite_sword`（r.0.0.mca 区块 (2,5) ×2、(0,2) ×1）——
  **这个世界的数据里钻石剑已经不存在**。
- **排除**：给弓 / 弓模式相关的机制全在证据链外——装备托管（`ProMaidExtension` 系列）把"给弓"引发的
  装备事件全落定（玩家侧 / 她身上 / 光标，日志三档都干净），且托管**只覆盖装备槽**；钻石剑最后的位置
  是主手、时间点是死亡窗口，与"给弓"之间没有任何日志/事件。**盲区**：背包级物品移动（`maidInv` 内部
  取放）按设计无日志无事件、死亡窗口不经托管——"被吞"能证明发生在死亡窗口之后，但指不出哪一步。
  按红线「有证据才动手」：**不改任何物品路径**，盲区登记 TD-22。

## 改动

- `promaid_src/com/maidsmart/follow/MaidChunkLoadManager.java`：去 5 格闸（`:979`）；六处
  `teleportCore(…, true)` → `teleportDirect`（`:1374/:1382/:1424/:1428/:1439/:1485`）；扫帚 / 坐骑
  落点 `standSpot` → `ownerBlock`（`:1561/:1699`）；新增 `ownerBlock`（`:1821`）/
  `teleportDirect`（`:1864`）/ `teleportToExact`（`:1880`），`teleportCoreTo` 变包装（`:1854`）；
  `summonAll` / `summonMaidTo` / `teleportCore` / `recallFromFlight` 四处 javadoc 同步。
- `promaid_src/com/maidsmart/schedule/SchedulePacketsMaid.java`：`MaidTeleportToPacket` 两分支直达
  她坐标（`:400-413`）；`SummonPacket` 空集合文案（`:78`）随去距离限制修正（"都在身边"不再触发空结果）。
- NEO 镜像两文件（Mojmap：`level()` / `getX()/getY()/getZ()` / `getYRot()/getXRot()` / `teleportTo`），改动面逐一对应。
- **触及文件：4 个**（两树 × `MaidChunkLoadManager.java` + `SchedulePacketsMaid.java`）。

## 验证

- 命令 + 结果：
  - 两树 `javac` **EXITCODE=0**（`gen_compile.py`+`compile_promaid.bat` / `gen_compile_neo.py`+
    `compile_neo.bat`；护栏整改后按**最终源码**复跑一轮，仍全 0）。
  - 14 护栏**全 FAIL 0 / WARN 0**。`check_comment_style` 初稿报 2 处 LONG-BLOCK（`teleportCore`
    javadoc 9 行 > 8，两树各 1）→ 按限长压缩后 PASS。
  - 双 jar（**同号重打**）：`patched/promaid-1.3.3-forge-1.20.1.jar` **17917858** 字节，sha256
    `835c66ee960cd1ef55e8300423892036fcfea50396b8a6e68266e250d9eff999`；`patched/promaid-1.3.3-
    neoforge-1.21.1.jar` **17965453** 字节，sha256 `d31f318f9c40a110c44784ab1dacbdbad189c2d7838
    7c99a9e22b66f53e0b0a0`。构建内混入审计 + lang json 校验 + 桌面快照同步全部 PASS。
  - **部署 4 实例：全部 `match=True`**（`python tools\deploy\deploy_all.py`；R996 + R997 **一并**部署，
    旧字节自动备份进 `patched/backup_old/` ×4）。R996 当轮的"被运行中的游戏挡住"已解除：本轮开始前
    复查 `tasklist` 无 java 进程，硬门禁放行。
  - 专服回归三件**全 PASS**：`test_server.py 1201`（Done 3.132s）、`test_server.py neoforge1211`
    （Done 0.989s）、`test_maid_load.py neoforge1211`（女仆召唤加载链，verdict PASS）。
- **反证**（改动 / 取证轮必填）：
  - "距离闸只有一处"：全树枚举 `findStandNear` / `< 5.0` / `已在身边` ——人工三键路径上**只有**
    `summonAll` 那一处 5 格闸（本轮删除）；其余"5 格内"判据全部位于自动路径（`SelfPreservation
    Behavior` 自保救援、`MasterDeathTeleportHandler` 主人死亡传送），本轮未动，改动面收得住。
  - "落点旧口径确实会偏"：改前人工传送要过 `findStand`（主人身边 16 格向下扫 16 格、向上扫 12 格
    找可站立格）——主人站的地方若"不可站立"（船上/半砖边/镂空），她就会落在附近别处；这正是
    "集合了但不在脚边"的观感来源。新口径 `teleportDirect` 不做任何搜索，直接 /tp 到坐标。
  - "钻石剑不是被弓模式/给弓吞的"：见「评估」——日志里给弓前后该剑没有一条移动记录；装备托管对
    "给弓"类事件的三档日志全部落在玩家侧 / 她身上；同期对照（下界合金剑三死仍在手、满背包撑过
    约 20 次魂符往返）证明物品路径按设计保物；存档全扫已无该剑——**给不出责任代码，也不猜改**。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）

- 状态：`未验收`（等开发者按「排班」清单实机复看三键手感与回归对照）。
- 未验收之前：不写 changelog、不写 release 说明、不发平台。

## 边界（没动什么）

- 距离限制取消**仅限三处人工按钮**（列表页集合 / 详情页召她过来 / 详情页去她身边）＋其载具落点；
  **自动路径落点与闸门一字未动**（受困救援 / 守家牵引 / 远距拉回 / 跟随 / 主人死亡传送 / 自保救援）。
- 直达坐标 = **/tp 语义**：主人飞在空中 / 悬在虚空时她同行（不再落地找格）；她 / 你会落在与目标
  **重叠**的位置（和 /tp 一样，胶囊体会互相推开）；去她身边时她若在空中，你到空中后自然下落
  （坠落伤害与 /tp 相同，未额外豁免）。
- 扫帚 / 坐骑的落点是"**主人所在格**"（格心 +0.5），不是主人身体的精确小数坐标——四步搬运需要一个
  `BlockPos` 把人和载具放进同一格；与精确坐标的误差在一格之内。
- home（守家）仍是唯一豁免；坐着 / 乘普通载具的「一键集合」仍保持原位（老口径）；「召她过来」仍
  只拽人不拽载具。行为语义按开发者指令直接更改，**不新增开关**（先例：实测九百二十五）。
- 钻石剑：本轮**未改任何物品路径**（无证据不猜改）；调查盲区（背包级取放无日志、死亡窗口不经
  装备托管）已登记 `DEBT.md` TD-22。
- 版本号仍 **1.3.3**（未发布时段同号重打，旧字节在 `RELEASES.tsv` 记废）。

## 台账

- 清掉：无既有债号（BD-31 上轮已清）。
- 新增债：`DEBT.md` TD-22（物品守恒可审计性盲区：`maidInv` 级取放无日志、死亡窗口不经装备托管——
  本轮取证暴露、只登记不修；§0 模块索引「性能 / 周期任务 / 日志」行补 TD-22）。
- 发布登记：`rounds/RELEASES.tsv` 追加 **1.3.3** 两条 `workspace`（**第一百三十三次同号重打**：
  本行现字节；前一次 `910b4cd5…9d5d9e` / `0ebf80bd…6cf86e` 作废）；**并更正 R996 两条行尾**——
  原写"部署 match=True；专服回归三件 PASS"与当轮记录矛盾（R996 当次被运行中的游戏挡住、未绕过），
  改为照实：该字节已作废、随本轮一并部署（`match=True` ×4）并回归（三件 PASS）。
- 升号：无（1.3.3 未发布 → 同号重打，§5.5 清单不动）。
