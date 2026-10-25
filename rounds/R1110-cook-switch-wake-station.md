# R1110 烧制换台/成品回收、叫醒判定放宽与语音、缺料提醒、炉子与酿造站位

## 排班

- **跑道**：A 修 bug（烧制换台、站位）+ B 新功能（叫醒判定、缺料气泡、工作流规则）。
- **开工前读过**：`MaidCookBehavior` 的 `tick` / `processFurnace` / `rebindAfterCycle` /
  `campfireStandSpot`，`MaidBrewBehavior.tick` 的站位分支，`MaidWakeHandler` 与
  TLM `MaidBedTask`，`tools/voice/` 三支脚本（`gen_voice_pack.py` / `norm_voice_pack.py` /
  `install_voice_pack.py`），`WORKFLOW.md` 的硬红线与 §6 文案纪律。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 最后一块还在烧时不许换台 | 炉子进料槽 / 营火槽还有料就继续守着 | ☑ |
| 1 | 附近 5×5×5 成品栏自动进包 | 背包 → 附属容器，塞不下留槽 | ☑ |
| 2 | 叫醒判定放宽到"原版右击村民"口径 | 右击物品走膨胀射线、右击方块走 1.5 格 | ☑ |
| 3 | 新语音用 `API.bat (2).lnk` 训练并导入两树 | 3 条 ogg 进 jar，manifest 匹配 | ☑ |
| 4 | 没燃料 / 没烧制物时气泡提醒主人 | 状态变化时一句，走既有气泡/TTS | ☑ |
| 5 | 炉子与酿造台只站邻格 | 与营火同一套 `stationStandSpot` | ☑ |
| 6 | 工作流写入"新气泡必须配语音"+快捷流程 | `WORKFLOW.md` 硬红线 11 + §6.3 | ☑ |
| 7 | 编译/护栏/打包/部署/测试 | 两树 OK；护栏全绿；四套 PASS；4 实例 match=True | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

"1.假设女仆手上有肉和矿，她先选择烧肉。当女仆烧到最后一块肉后，她不会等到烧完了再换炉子，而是直接
换炉子。会导致炉子里永远剩下一块。最好是女仆附近5*5范围内的烧制成品栏物品会自动进入女仆背包/附属
容器，女仆包满了除外
2.新语音需要训练，用"C:\Users\Sketch\Desktop\API.bat (2).lnk"。而且叫醒判定有点太小了，改成和原版
右击叫醒村民的判定一样
3.女仆在没有燃料/烧制物时需要有气泡对话提醒一下主人
4.对于炉子和酿造也加入一个与营火同样的判定，让女仆不要站在这个方块上面而是站在附近。否则女仆老是
跑到方块上面去很难看。（当然被迫站上去不会改位置，这里指的是寻路）
5.在工作流/记忆中加一点：如果要加入新气泡/语音，一定要用"…API.bat (2).lnk"训练语音并导入。同时为了
方便。你可以总结一套训练的快捷流程放入工作流，省得每次还要慢慢找。"

## 评估

1. 换台由 `tookOutputThisRound` 直接触发 `rebindAfterCycle`：她收走**上一批**的成品就当作"这一轮完成"，
   而进料槽里可能还有最后一块在烧。加一道 `stationBusy` 闸即可——炉子看进料槽（且这型炉子烧得动），
   营火看火槽是否非空。5×5 成品回收是第二道保险：路过任何炉子都把成品槽清进背包/附属容器。
2. 叫醒原来只挂 `EntityInteract`，依赖客户端准星精确命中睡着后缩小的碰撞箱；她躺在床上时更容易点到床
   方块而不是她。放宽为三条入口共用一套"找到睡着的她"判定，与原版右击村民同属"大致对着她就行"。
3. `processFurnace` 的 `nofuel` / `nofeed` 只写日志、dump 背包，玩家不知道她为什么停。把气泡挂在
   `logRound` 返回 true（状态变化）的分支上，天然不会每轮刷屏。
4. 炉子/酿造台的 `WalkTarget` 直接指向方块本身，寻路会把她送到方块顶上；营火早有
   `campfireStandSpot`，把它泛化成 `stationStandSpot` 后三处共用。四周无站位时退回旧行为（被迫站上去
   不改位置）。
5. 语音训练是外部 GPT-SoVITS 服务（`127.0.0.1:9880`），流程散在三个脚本里，容易漏步；写进工作流并
   固化成清单。

## 改动

### 烧制（`MaidCookBehavior`，两树）

1. **不许提前换台**：`tick` 末尾改为
   `if (this.tookOutputThisRound && !this.stationBusy(level)) rebindAfterCycle(...)`；
   `stationBusy` 对营火查 `getItems()` 是否有非空项，对炉子查槽 0 是否非空且 `canSmeltHere`。
2. **附近 5×5×5 成品回收**：新增 `sweepNearbyOutputs`，每轮（`processCooldown`，默认约 20 tick）
   以女仆脚下为中心扫半径 2 的范围，把每个 `AbstractFurnaceBlockEntity` 的成品槽（槽 2）搬进背包，
   再溢出到附属容器；两边都塞不下才放回原槽。收到东西就挥手并记 `sweep` 日志。
3. **缺料气泡**：新增 `say(maid, text)`；在 `nofuel`（无燃料）与 `nofeed`（无烧制物）状态**首次**
   出现时各说一句：
   - "没有柴火了…主人，能给我一点燃料吗？"
   - "背包里没有能烧的东西了…主人，给我一点吧？"
   走 `addTextChatBubble`，与既有气泡/TTS/聊天镜像同一条链路，受 `misc.promaidHints` 管。

### 站位（`MaidCookBehavior` + `MaidBrewBehavior`，两树）

- 把 `campfireStandSpot` 泛化为 `stationStandSpot`（脚下不实、头上不实、非营火/危险方块、工作区内，
  就近优先），营火、炉子系、酿造台三处共用。
- 炉子与酿造台：有邻格时以邻格为 `WalkTarget`（`closeEnough 1`），并且"已经站在台子格上"时先挪开；
  没有邻格则退回旧行为（走向方块本身，`closeEnough 2`），被迫站上去时不再强制改位置。

### 叫醒（`MaidWakeHandler`，两树）

- 三条入口共用判定：
  - `EntityInteract`：目标就是睡着的女仆（原逻辑）；
  - `RightClickItem`：视线 6 格 + 实体射线，AABB 膨胀 1 格，只认睡着的女仆，不穿墙；
  - `RightClickBlock`：右击方块（多半是她睡的床）周围 1.5 格内最近的睡着的女仆。
- 三条都要求手持本模组物品；命中即取消事件、挥手、走原 `wake()`（停止睡眠、清寻路、60 秒断路、
  抱怨气泡）。
- 三个处理器提到 `EventPriority.HIGHEST`；`IndexStoneInteractHandler.onUseItem` 与
  `GunnerTetherManager.onUseItem` 增加 `event.isCanceled()` 早退，避免"叫醒的同时又绑了人"。

### 语音与工作流

- 新增 3 条语音（两树 manifest + `assets/promaid/voice/*.ogg`）：
  `wake_complain.ogg` / `cook_no_fuel.ogg` / `cook_no_food.ogg`；
  `tools/voice/gen_voice_pack.py` 增加对应日文台词与情绪档（care / plead）。
- `WORKFLOW.md` 新增硬红线 11 与 §6.3「新气泡 / 新语音（硬性：必须训练并导入）」，包含一键流程
  与常见坑（manifest 文本必须逐字一致、只起一个 API 实例、`_voice_new2` 用完即删）。

## 验证

- Neo 编译 `EXITCODE=0`；Forge 编译 `EXITCODE=0`（Forge 侧 SRG：`BlockPos.m_7918_/m_121940_`、
  `ItemStack.m_41613_`、`Container.m_8016_`、`CampfireBlockEntity.m_59065_` 等）。
- 护栏全绿：14 项 `FAIL 0 / WARN 0`，`check_comment_style: RESULT: PASS`；注入点 293。
- 语音：`gen_voice_pack.py check` 的 `missing JP` 为空；三条合成成功，
  `norm_voice_pack.py` 归一化后 43~46 KB/条；`install_voice_pack.py` 两树各拷贝 3 条；
  jar 内确认 manifest 184 条且三条 ogg 都在。
- 四套专服回归 PASS（1201 / neoforge1211 × server / maid_load）。
- Neo jar：18,376,336 字节，SHA256
  `5BA01137BA32445B36CBF53A6038F82B0B07A842421392D5D2A3071633000D8E`。
- Forge jar：18,315,379 字节，SHA256
  `9038AFA000EF502A874C22CD3CC9F7AC24E1B777F7C51207BF6F8D36B3403BB9`。
- 已部署 4 实例，`match=True`。

## 边界

- 5×5 回收只认**炉子系**（熔炉/烟熏炉/高炉）的成品槽，不碰酿造台与其它容器；背包与附属容器都满时
  成品留在原槽，不销毁、不丢地。
- 换台闸只保证"她不会在还有料要烧时离开"；如果炉子里放的是这型炉子烧不动的杂物，`canSmeltHere`
  为假，她仍会按原逻辑取回并换台。
- 叫醒放宽的是**命中判定**，不是无限制：射线 6 格、方块周围 1.5 格、不穿墙；仍要求手持本模组物品，
  且只对"正在睡觉"的女仆生效。
- 炉子/酿造台四周真的没有可站邻格时（贴墙、岩浆、悬空），退回原行为；已经被挤到方块顶上也只保持原位，
  不会为了好看强行瞬移。
- 本轮没有新增配置开关：换台/站位/叫醒都是对既有行为的修正，5×5 回收只在"烧制任务"激活时运行——
  关掉烧制任务即停，符合性能纪律里的"哪个开关能关掉它"。
- 三条新语音默认随 jar 分发；`misc.promaidHints` 关闭时气泡与语音一起静默（预期）。

## 验收

1. **最后一块肉**：背包里先放肉再放矿，让她在熔炉烧肉；收到第一批成品后**不会**立刻换台，
   等进料槽空（最后一块烧完并收走）才换去烧矿。
2. **5×5 成品**：在她附近 2 格内的炉子里预先烧好一组铁；烧制任务激活时她路过就会收进背包/附属容器，
   背包与附属容器全满时留在炉子里。
3. **叫醒**：拿武装拴绳/指标石，准星大致对着睡着的她（或右击她睡的床）→ 她被叫醒并说
   "唔…主人，人家还想再睡一会儿嘛～"，同时有语音；60 秒内不回去睡；这期间按右键不会误触发绑定。
4. **缺料提醒**：清空她的燃料 → 首次出现"没有柴火了…"气泡并有语音；清空可烧物 →
   "背包里没有能烧的东西了…"；补上后不再重复。
5. **站位**：看她操作熔炉/烟熏炉/酿造台——站在**旁边一格**，不再站在方块顶上；
   把四周堵死时退回旧行为，不瞬移。
