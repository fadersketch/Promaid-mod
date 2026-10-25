# R1108 远距离选女仆（512 格）+ 绑定/准星描边

## 排班

- **跑道**：C 功能（两树镜像）。
- **开工前读过**：`IndexStoneInteractHandler` / `IndexStoneService` / `IndexStonePreviewClient` 的
  绑定链路，`GunnerTetherManager` 的拴绳挂点与绑定状态，`MaidGlowGoldMixin`（已有的金色上色），
  以及参考实现——`[傀儡装配] modulargolems-3.1.44.jar` 的命令权杖：l2library 的
  `RayTraceUtil` + `IGlowingTarget`，客户端在 `Entity.isCurrentlyGlowing` 上注入，走的是
  光谱箭同一条 `OutlineBufferSource` 渲染路径。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 服务端 512 格长射线选女仆 | 眼睛沿视线 512 格，方块裁剪挡墙 | ☑ |
| 1 | 指标石右键走长射线绑定/解绑 | `RightClickItem` + 原绑定服务 | ☑ |
| 2 | 武装拴绳右键补长射线兜底 | 原交互距离内选不中时再走 512 格 | ☑ |
| 3 | 被绑女仆有光灵箭同款描边 | `setGlowingTag(true)` + 自有标记，解绑撤掉 | ☑ |
| 4 | 手持物品且准星指女仆也有描边 | 客户端 `isCurrentlyGlowing` 注入，移开撤掉 | ☑ |
| 5 | 交互距离内不被客户端预览抢走 | `aimsAtFarMaid` 直接让给服务端 | ☑ |
| 6 | 编译/护栏/打包/部署/测试 | 两树 OK；护栏全绿；四套 PASS；4 实例 match=True | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

"武装拴绳/指标石选中女仆不再局限于玩家交互距离，改为512格交互距离，指标石绑女仆同类，被绑女仆会有
光灵箭同款描边，解绑后解除描边。当玩家手持这两个物品且指针对准女仆时，女仆也会获得描边，移开指针后
解除（详细实现参考傀儡装配中的命令权杖对准女仆/傀儡时的…）"

## 评估

原版 `PlayerInteractEvent.EntityInteract` 只在玩家交互距离（生存 ≈3 格、创造 ≈5 格）内触发，
所以两件物品的"选中"天然被卡在这个半径里。要 512 格，必须自己在服务端做一次实体射线，不能改
玩家交互距离（会连带影响所有交互）。

描边有两条完全不同的生命期，分开做：

1. **绑定期**：状态在服务端（谁绑了谁），用原版发光标记 `setGlowingTag(true)`，客户端照常渲染，
   现有的 `MaidGlowGoldMixin` 把金边统一上色。解绑、换人、魂符回收、快照清洗都要撤。
2. **准星指向**：纯客户端、每帧都可能变，不写服务端状态、不发包。照命令权杖的做法，在
   `Entity.isCurrentlyGlowing()` 上注入，返回 true 就走光谱箭的描边缓冲。

## 改动

### 服务端（两树）

- **`tool/MaidFarPick`（新）**：`pick(ServerPlayer)` 从眼睛沿视线 512 格。
  先做同长度的方块裁剪（`ClipContext.Block.OUTLINE`，不穿墙），再用 `ProjectileUtil`
  找最近的 `EntityMaid`；命中点比方块命中点更远（+0.5 容差）就判为隔墙、返回 null。
  异常一律 `Diag.swallow`，最坏只是这一下没选中。
- **`build/IndexStoneInteractHandler`**：新增 `RightClickItem` 监听——手持指标石且长射线选中女仆时
  取消物品使用、挥一下手、调 `IndexStoneService.bindOrUnbind(player, maid)`，再 `syncTo(player)`。
  交互距离内的原 `EntityInteract` 路径不动。
- **`combat/GunnerTetherManager`**：`onUseItem` 在原有几种选中方式都失败后补一次 `MaidFarPick.pick`；
  绑定成功一律 `markLeash(maid)`（挂点与拴绳都算绑定），解开挂点不再顺手撤描边，`onMaidJoin`
  重建与 `restore` 乘客分支同样补标记。
- **`build/IndexStoneService`**：新增 `TAG_INDEX_GLOW = "maid_smart_index_glow"`；
  `markOutline`（`setGlowingTag(true)` + 写标记）在绑定成功后调用；`unmarkOutline`（只有标记在才撤，
  避免把别人打的光灵箭效果清掉）在解绑、释放、清标记三处调用；`sanitizeSnapshot` 遇到本标记时
  删掉 `Glowing` 字段再写回 NBT——魂符收回/放下不会带着描边残留。

### 客户端（两树）

- **`client/MaidAimGlowClient`（新）**：每客户端 tick 看主手——指标石（受 `IndexStoneService.isEnabled()` 管）
  或武装拴绳（受 `GunnerTetherManager.isEnabled()` 管）——`camera.pick(512, partial, false)`，
  命中 `EntityMaid` 就记成"当前瞄准"，没命中 10 tick 后淡出清除；
  `isAimed(Entity)` 校验身份、存活、未移除。
- **`mixin/MaidAimGlowMixin`（新）**：`@Mixin(Entity.class)` 注入 `isCurrentlyGlowing()` HEAD，
  客户端侧且 `MaidAimGlowClient.isAimed(self)` 时直接返回 true。整段是纯视觉，不改服务端状态。
- **`build/IndexStonePreviewClient`**：新增 `aimsAtFarMaid`；`onInteraction` 在近距实体分支之后，
  若准星指着远处女仆就直接返回，让服务端的 `RightClickItem` 接管（锁定状态下也一样）。
- **`client/PromaidClientSetup`**：注册 `MaidAimGlowClient.ensureRegistered()`。
- **`mixins.promaid.json`**：client 列表加 `MaidAimGlowMixin`。

## 验证

- Neo 编译 `EXITCODE=0`；Forge 编译 `EXITCODE=0`（本轮另修了 Forge 侧 SRG：`Vec3.add →
  m_82549_`、`player.level() → m_9236_` 等）。
- 护栏全绿：`check_mixin_targets / preset / workflow / catch_swallow / i18n_keys / dev_voice /
  comment_dup / reflection / arch / guide_i18n / panel_coverage` 均 `FAIL 0 / WARN 0`，
  `check_comment_style: RESULT: PASS`；注入点 293（下限 200）。
- 四套专服回归 PASS（1201 / neoforge1211 × server / maid_load）。
- Neo jar：18,240,445 字节，SHA256
  `849659892FDB199A6CADC60134682A2993BA0E9BFADE8E585AE7BB3CAB8E5822`。
- Forge jar：18,179,494 字节，SHA256
  `FF064538E2397A9AC0CAF4DC41D3A8EF82E5E73DF24BD00ABD2AACCB1718287B`。
- 已部署 4 实例，`match=True`。

## 边界

- 512 格是**选人**距离，不是施法距离：选中之后原有的绑定/所有权/任务切换规则一字未改。
- 不隔墙选人：方块裁剪命中点更近时返回 null；隔着玻璃（非遮挡方块）仍然可选。
- 准星描边是纯客户端视觉，其他玩家看不到；服务端只认绑定期描边。
- 绑定期描边用的是原版发光标记，会和光灵箭共用同一条渲染通道；撤除时只撤自己写的标记，
  不会误伤别人给的光灵箭。
- 客户端瞄准检测只认主手；副手持物品不算。

## 验收

1. **指标石**：站在 100 格外对准女仆按右键——她开始搭建，界面无"距离太远"提示；
   目标被墙挡住时按右键不绑定。
2. **武装拴绳**：同样远距离对准按右键能挂上；解绑后描边消失。
3. **绑定期描边**：绑定后从远处看她是金色描边（与光灵箭同款），解绑立刻消失；
   魂符收回再放下，描边不再出现。
4. **准星描边**：手持指标石/武装拴绳，准星扫过女仆时她出现描边，移开消失；
   手里换成别的物品不出现。
