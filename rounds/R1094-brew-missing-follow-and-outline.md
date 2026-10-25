# R1094 酿造缺料报告、工作站跟随锁、金框跟手

## 排班

- **跑道**：反馈三条（酿造缺料不报 / 酿造时仍跟主人 / 金框慢一拍），
  后续补充「烧制也有同样的跟随问题」，两树镜像。
- **开工前读过**：`MaidBrewBehavior`（缺料状态表、燃料槽与水瓶槽处理）、
  `MaidCookBehavior`（炉子绑定/收回/看门狗）、`MaidWorkTags`（站桩/链路独占标记）、
  `MaidFollowOwnerTickMixin`、`MaidMoveSuppressMixin`、
  `MaidChunkLoadManager`（跨维/同维拉回）、
  `PlacedMarkNetworking` / `PlacedMarkClient` / `PlacedBlockTracker`、
  语音包 manifest（确认本轮没有新增台词）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 缺料状态按材料分开 | 同时缺燃料与水瓶都能各报一次 | ☑ |
| 2 | 燃料槽取不到烈焰粉时报告 | `notifyMissing("minecraft:blaze_powder")` | ☑ |
| 3 | 水瓶与可续酿基底都取不到时报告 | `notifyMissing("minecraft:water_bottle")` | ☑ |
| 4 | 绑定有效酿造台后掐断 TLM 跟随 | 标记 + 跟随 start 取消 | ☑ |
| 5 | 烧制绑定炉子后同样掐断跟随 | 酿造/烧制共用工作站锁 | ☑ |
| 6 | 跨维/同维自动拉回让位 | 绑定期间不把她拽走 | ☑ |
| 7 | 金框增删立即发送 | 放置当 tick 发包；对账补帧仍合并 | ☑ |
| 8 | 编译、护栏、重打、部署、测试 | 两树 `EXITCODE=0`；14 护栏 `FAIL 0`；四套测试 PASS | ☑ |

## 一、酿造缺料报告

- 缺料状态原来是「按女仆存一份」：`MISSING_PENDING` / `MISSING_CD` /
  `LAST_MISSING` 都只用女仆 UUID 作键。同时缺烈焰粉和水瓶时，后一个材料会把
  前一个的 pending 与去重记录顶掉，两条报告互相盖掉。现在统一改成
  `女仆 UUID + 材料 id` 的键（`MISSING_REPORTED` 替代 `LAST_MISSING`），
  两种材料各走各的双通道确认与 30 秒冷却。
- 燃料槽（槽 4）取不到烈焰粉时不再静默：首次记 pending，下一轮仍缺就气泡 +
  主人消息。成功补燃料时清掉该材料的缺料记录。
- 定向/批量两条路的水瓶槽：水瓶取不到且没有可继续推进的成品药水当基底时，
  报「缺少水瓶」；只要取到水瓶或基底就清掉水瓶缺料记录。
- 补料成功的各条路径（下界疣、正向材料、强化材料、形态材料）都改为清对应
  材料自己的记录，不再误清其他材料的缺料状态。
- `m_6732_`/`stop` 停止行为时清空该女仆的全部缺料记录，静态表不再跨任务残留。
- 顺手修了一处潜在空指针：`claimBrew` 发现台子被别的女仆占用时会清空
  `standPos`，旧代码紧接着拿 `null` 去查方块；现在该分支立即回到「边走边找」。

## 二、工作站跟随锁（酿造 + 烧制）

- `MaidWorkTags` 新增 `STATION_LOCK_TAG` 与 `setStationLock` / `isStationLocked`。
  判定带任务兜底：标记在但任务已不是 `maid_smart:brew` / `maid_smart:cook` 时
  恒 false，防残留。
- `MaidBrewBehavior` 在绑定台子通过「方块仍是酿造台 + 工作区允许」两项校验后
  置锁；`MaidCookBehavior` 在绑定炉子通过「方块仍是炉子 + 工作区允许 + 背包有
  该炉型可烧物」三项校验后置锁。台子/炉子被破坏、跑出工作区、切出对应任务、
  行为停止（含烧制 60 秒零进展看门狗）时清锁。
  锁从「绑定成功」起生效，所以她走去工作方块的路上也不会被跟随行为改写寻路。
- `MaidFollowOwnerTickMixin` 的 `start` 取消闸加入 `isStationLocked`：跟随时
  「写回主人寻路」与「过远传送」两段都不再执行。
- `MaidChunkLoadManager` 的跨维度自动跟随与同维度远距拉回都加入工作站锁豁免，
  避免 TLM 之外的兜底拉回把正在看台/看炉的她拽走。
- 玩家主动召回（排班表 `summonOne` / `summonMaidTo`）不受影响：人工意图优先。
- 与 `WORK_STILL` 的分工：站桩锁只在走到工作方块前才置（走路时不能冻结移动），
  工作站锁从绑定起就生效。
- 顺手修了烧制侧同款潜在空指针：`claimFurnace` 发现炉子被别的女仆占用时会清空
  `furnacePos`，旧代码紧接着拿 `null` 去查方块；现在该分支立即回到「边走边找」。

## 三、金色描边跟手

- 服务端原来把真实放置/拆除也按 5 tick 合并发包：连续搭路时刚放下的那一格
  要等下一次 flush 才到客户端，看起来永远慢一格（「黄色光标有一个方块左右的
  延迟」）。
- 现在 `noteAdd` / `noteRemove`（真实放置与拆除）立即发包；只有对账补帧这种
  一次可能补几百格的批量场景仍走 5 tick 合并。方块本身一两秒才一格，逐格发
  不会造成包量问题。
- 客户端渲染逻辑未改：收到包即写表，每帧按「方块还在且仍是登记时那块」绘制。

## 改动文件

- 两树 `task/MaidBrewBehavior.java`
- 两树 `task/MaidCookBehavior.java`
- 两树 `task/MaidWorkTags.java`
- 两树 `mixin/MaidFollowOwnerTickMixin.java`
- 两树 `follow/MaidChunkLoadManager.java`
- 两树 `task/PlacedMarkNetworking.java`

## 验证

- Forge / Neo 编译均 `EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`（注入点 285）。
- 专服回归：`test_server.py 1201` PASS、`test_maid_load.py 1201` PASS、
  `test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS。
- Forge jar：18,139,773 字节，SHA256
  `CB4CCFEE39858D5C88FF9ECE5EF24B99CFFFC847D553226EFB12EB0675ED402B`。
- Neo jar：18,199,756 字节，SHA256
  `C07BB0E0E1C7C95BD63922D64F9ACDFA334FEE9877750D9A94161ABD380452B2`。
- 已部署 4 实例，`match=True`。

## 边界

- 燃料只认原版酿造台的燃料槽物品（烈焰粉）。原版 `BrewingStandBlockEntity`
  的燃料判定就是烈焰粉，烈焰棒放不进燃料槽，因此报告文案是「烈焰粉」。
- 水瓶报告只在「水瓶与可续酿基底都没有」时发出；背包里有能继续推进的成品
  药水时不会误报缺水瓶。
- 缺料是双通道确认：第一轮只记 pending，下一轮仍缺才真正发消息；补上材料后
  同一种材料下次缺料会重新确认后再报。
- 工作站锁只在「任务确为酿造/烧制 + 绑定方块仍有效且在工作区内」时成立；
  烧制另要求背包里有该炉型能烧的东西。切模式、方块被破坏/被替换、跑出工作圈、
  烧制看门狗解绑都会立刻解除。
- 本轮没有新增语音台词：缺料气泡仍是「主人，酿造缺材料：…」这一句，内置语音包
  的 `brew_missing.ogg` 以 `contains` 命中「酿造缺材料」，无需补录或训练。
- 金框立即发送只影响真实放置/拆除；进服快照与对账补帧仍按原分片/合并策略，
  不改变断线重连与大存量的包量。

## 验收

1. 酿造台没有燃料、背包也没有烈焰粉：女仆气泡与主人聊天各收到一次「缺少
   烈焰粉」；补上后继续酿。
2. 三个药水槽空、背包既没有水瓶也没有可续酿药水：收到「缺少水瓶」；只放
   水瓶后恢复下料。
3. 同时缺烈焰粉和水瓶：两条报告都能收到（不再互相覆盖）。
4. 酿造任务绑定到有效酿造台后，主人走远/换维度：女仆留在台前继续酿，
   不再被跟随或自动拉回；切出酿造任务或拆掉台子后跟随立刻恢复。
5. 烧制任务绑定到有效炉子（熔炉/高炉/烟熏炉）后，主人走远/换维度：女仆留在
   炉前继续烧，不再被跟随或自动拉回；切出烧制任务、拆掉炉子或 60 秒零进展
   解绑后跟随立刻恢复。
6. 连续搭路时金色描边与刚放下的方块同 tick 出现，不再慢一格。
