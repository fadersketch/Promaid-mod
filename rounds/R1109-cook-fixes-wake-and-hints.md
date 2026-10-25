# R1109 烧制五修 + 叫醒睡着的女仆 + 提示总开关

## 排班

- **跑道**：C 功能（两树镜像）。
- **开工前读过**：`MaidCookBehavior` 的 `equipIgniterToMainHand` / `processFurnace` /
  `processCampfire` / `rebindAfterCycle` / `stop` 五条离台路径，`MaidBedInteropBehavior`
  与原版/TLM 两条找床链路，`ChatBubbleManager` 的气泡链（`ChatBubbleLimitMixin` 负责静音/限流/TTS/聊天镜像），
  `MaidSmartConfig` 的 `misc` 段与 `MaidConfigPreset` 的 DIY 保留表。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 打火石检索补副手 | 主手无、副手有 → 直接互换后点燃 | ☑ |
| 1 | 点火/放取加挥手 | 点火、投料、取料、收成品都有主手动作 | ☑ |
| 2 | 离台前先收回成品 | 5 条离台路径都先 `harvestStation` | ☑ |
| 3 | 本模组物品右击睡着的女仆 → 叫醒 | 停止睡眠 + 抱怨气泡/语音 + 断找床 60 秒 | ☑ |
| 4 | 收放魂符清空断路 | `ToItem` 事件删掉冷却 | ☑ |
| 5 | 气泡/系统消息总开关 | 配置 `misc.promaidHints`，只静默本模组的 | ☑ |
| 6 | 编译/护栏/打包/部署/测试 | 两树 OK；护栏全绿；四套 PASS；4 实例 match=True | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

"1.女仆在检查打火石的时候只检查背包不检查副手
2.女仆在点火/往炉子里放物品/收取时要有一个手上动作
3.女仆必须先把烧好的东西拿回背包/…再解绑这个炉子或者烧制工具。否则女仆永远会在切换的时候还落了一个
烧制物在炉子里面。
4.用本模组物品右击睡觉的女仆可以把她叫醒，并让她播放一条抱怨文本及语音。并让她的找床睡觉链路断路60秒。
（收放魂符会清空掐断）
5。是否保留本模组加入的气泡和系统消息也作为一个配置项"

## 评估

1. 点燃道具的检索只走 `getAvailableBackpackInv()`，而她实际会把手里的东西拿在副手——
   `equipIgniterToMainHand` 只比较主手，于是"身上明明有打火石却说没有"。
2. 烧制链路是纯容器搬运，没有任何 `swing`，看起来像隔空施法。
3. "切换时炉子里落一个成品"有两层根因：
   - 炉子→营火切换、工作区越界、材料不匹配、完成一轮重选、行为停止这 5 条路都直接 `releaseFurnace`，
     没有先取成品槽；
   - 收成品时只要塞进去一部分就置 `tookOutputThisRound = true`，看起来"这一轮已完成"，
     剩下的余量留在炉子里再没人管。
4. 睡觉中的女仆吃 `EntityInteract`，但原版只会在交互距离内触发；叫醒本身容易，
   麻烦的是**断掉两条找床链路**：TLM 自带的 `MaidBedTask`（女仆床）和我们自己的
   `MaidBedInteropBehavior`（原版床）。
5. 气泡/系统消息分散在几十处，逐个加开关不现实；统一在出口处拦。

## 改动

### 烧制（`MaidCookBehavior`，两树）

1. **副手打火石**：`equipIgniterToMainHand` 在主手不是点燃道具时先看副手，是就主副互换
   （不消耗、不复制），再走原有点燃；`hasIgniter` 仍按"手 + 背包 + 附属容器"判断。
2. **挥手**：点燃（打火石/火焰弹）、投料、补燃料、取回不匹配物、收炉子成品、营火摆料、
   营火收掉落、离台收成品，共 9 处 `swing(MAIN_HAND)`。
3. **离台先收成品**：新增 `harvestStation(level, maid)`——营火走 `collectCampfireDrops`，
   炉子取成品槽（背包 → 附属容器溢出，塞不下的放回成品槽）。在 5 条离台路径调用：
   炉子→营火切换、工作区越界、材料不匹配、`rebindAfterCycle`、`stop`。
   收成品改成**全部收走才算完成**：`tookOutputThisRound` 只在 `left.isEmpty()` 时置 true，
   部分收走仍算没完成，下一轮继续。
4. 顺带把营火掉落收集抽成 `collectCampfireDrops(level, maid, pos)`，两处共用。

### 叫醒（新增，两树）

- **`task/MaidWakeHandler`**：`BED_BLOCK_UNTIL`（UUID → 解禁 gameTime，1200 tick = 60 秒）；
  `isBedBlocked(maid)` 供两条链路查询并顺手清理过期项。
  `PlayerInteractEvent.EntityInteract` 里：目标是睡着的 `EntityMaid`、玩家是服务端、手持物品
  命名空间是 `maid_smart`（武装拴绳/指标石/蓝图书……全部算）→ 取消事件、挥手、
  `stopSleeping()`、清 `WALK_TARGET`、写 60 秒冷却、通过 `addTextChatBubble` 播抱怨
  （"唔…主人，人家还想再睡一会儿嘛～"，走既有气泡 → TTS/语音包链路）。
  另监听 `MaidAndItemTransformEvent.ToItem`（魂符收回），事件里的女仆在表里就删掉冷却。
- **`mixin/MaidBedWakeBlockMixin`**：注入 TLM `MaidBedTask.checkExtraStartConditions` HEAD，
  冷却中直接返回 false —— 女仆床这条链路不启动。
- **`task/MaidBedInteropBehavior`**：`checkExtraStartConditions` 第一行同样查 `isBedBlocked`。
- **`ProMaidMod`**：注册 `new com.maidsmart.task.MaidWakeHandler()`。
- **`mixins.promaid.json`**：common 列表加 `MaidBedWakeBlockMixin`。

### 提示总开关（两树）

- **`MaidSmartConfig`**：`misc` 段新增 `MISC_PROMAID_HINTS`（`promaidHints`，默认 true）：
  开 = 保留本模组的气泡、聊天镜像与操作提示；关 = 全部静默，但只管本模组。
- **`ChatBubbleLimitMixin`**：闸门关掉时 `cir.setReturnValue(-1L)` —— 所有走 TLM 气泡管理器的
  本模组气泡直接不生成（连带不 TTS、不镜像聊天）。
- **`tool/PromaidHintGate`（新）**：`shouldSuppress()` 在开关关闭时，遍历当前调用栈，
  跳过本 mixin 自己的帧，只要出现 `com.maidsmart.` 的帧就判定"这条消息来自本模组"。
- **`mixin/PromaidMessageGateMixin`（新）**：`@Mixin(ServerPlayer)`，在
  `sendSystemMessage(Component)` 与 `sendSystemMessage(Component, boolean)` 的 HEAD 取消，
  条件就是 `PromaidHintGate.shouldSuppress()`。原版与其他模组的消息调用栈里没有本模组帧，不受影响。
- **`PromaidClientSetup`**：无需改动；`misc.promaidHints` 已在配置界面自动出现。
- **`MaidConfigPreset`**：`"misc.promaidHints"` 加进 `KEEP`（DIY 关不掉它）。
- **lang**：`config.promaid.misc.promaidHints`（中/英）。

## 验证

- Neo 编译 `EXITCODE=0`；Forge 编译 `EXITCODE=0`（Forge 侧另修 SRG：`Brain.m_21936_`、
  `MemoryModuleType.f_26370_`、`ResourceLocation.m_135827_` 等）。
- 护栏全绿：`check_mixin_targets / preset / workflow / catch_swallow / i18n_keys / dev_voice /
  comment_dup / reflection / arch / guide_i18n / panel_coverage` 均 `FAIL 0 / WARN 0`，
  `check_comment_style: RESULT: PASS`；注入点 293（下限 200）。
- `check_arch` 从此前 FAIL（新文件未登记）回到 `FAIL 0 / WARN 0`：新增
  `promaid.system.wake` 模块，`MaidFarPick`/`PromaidHintGate` 归 `core.safety`，
  `MaidAimGlowMixin`/`MaidAimGlowClient` 归 `mixin.render-client`，
  `PromaidMessageGateMixin` 归 `mixin.dialogue-llm`；`ARCHITECTURE.md` 规模改为
  121 模块 / 61 API / 77 依赖箭头。
- 四套专服回归 PASS（1201 / neoforge1211 × server / maid_load）。
- Neo jar：18,240,445 字节，SHA256
  `849659892FDB199A6CADC60134682A2993BA0E9BFADE8E585AE7BB3CAB8E5822`。
- Forge jar：18,179,494 字节，SHA256
  `FF064538E2397A9AC0CAF4DC41D3A8EF82E5E73DF24BD00ABD2AACCB1718287B`。
- 已部署 4 实例，`match=True`。

## 边界

- 副手打火石是**互换**不是复制：换完主手拿打火石、副手拿原先的主手物。
- `harvestStation` 塞不进背包/附属容器的成品会放回成品槽，不做销毁；等她下次回来或玩家取走。
- 叫醒只认本模组命名空间（`maid_smart`）的物品；原版物品、别的模组物品照旧交给原逻辑。
  右击会取消事件并挥手，但不会消耗物品。
- 60 秒断路按**女仆 UUID** 计，与谁叫醒的无关；收放魂符只清"被收回的这位"的冷却。
- 提示总开关只管本模组：原版系统消息、别的模组消息、TLM 自己的对话框不受影响。
- 关掉提示后，依赖气泡文本的功能（例如抱怨台词）只是不显示/不出声，不会改变行为本身。

## 验收

1. **副手打火石**：只手副手放打火石，靠近熄灭营火——她能点燃，界面里打火石从副手换到主手。
2. **挥手**：站在旁边看，点火、往炉子放料、取料、收成品都有抬手动作。
3. **离台不落成品**：炉子里刚烧好一组铁，立刻让她去做别的事（换台/离开工作区/停下）——
   成品在背包或附属容器里，炉子成品槽是空的。
4. **叫醒**：拿武装拴绳右击睡着的女仆——她站起来、冒一句"唔…主人，人家还想再睡一会儿嘛～"
   并有语音；接下来 60 秒内不会自己回去睡；收进魂符再放出来，冷却清空（会正常找床）。
5. **提示开关**：配置里把「气泡与系统消息」关掉——本模组的气泡、聊天镜像、操作提示都不再出现；
   原版与其他模组的消息照常；重新打开立刻恢复。
