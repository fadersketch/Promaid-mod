# R1102 蛋糕驯服被抢：探测不到「喂食抢答型」附属的根因与修复

## 排班

- **跑道**：C 功能（两树镜像）。
- **开工前读过**：`MaidCakeEatHandler`（两树）、`MaidCakeEdibleMixin`（1.20.1）、
  `ProMaidMod`/`ProMaidExtension` 的注册点；反编译的 **solmaid-1.0.1**（两版本：
  `com.mastermarisa.solmaid.event.MaidFeeding`、`FilterHelper`）与 TLM 1.20.1
  `EntityMaid.m_6071_/tameMaid`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位冲突模组 | `solmaid`（生活调味料：酒狐萝贝），两实例都在装 | ☑ |
| 1 | 反编译实证其行为 | 不改蛋糕；把可食用物喂给**任意**女仆并 setCanceled | ☑ |
| 2 | 对照 TLM 驯服链 | `mobInteract → tameMaid`（蛋糕是驯服物）被取消即断 | ☑ |
| 3 | 修复：启动探测扩到「喂食抢答型」附属 | `FEED_HIJACKER_MODS` 名单 + ModList 检测 | ☑ |
| 4 | 修复：运行时哨兵兜底 | LOWEST 优先级看「野生女仆+蛋糕被别人取消且被塞了蛋糕」 | ☑ |
| 5 | 修复：自己的取消让路 | `onInteract` 见 isCanceled 直接 return（防双喂） | ☑ |
| 6 | 实测验证 | 带 solmaid 启动 1201 测试服：日志出现"检测到冲突…自动关闭" | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

1. "1.3.3beta 和生活调味料酒狐萝贝冲突，会导致没法用蛋糕驯服新的女仆。但明明 1.3.3beta
   版写了：蛋糕可食用：检测到其他模组改过蛋糕时自动关闭……看一下为什么探测不出来。"

## 评估（为什么探测不出来）

**探测没坏——它探的信号在这场冲突里根本不存在。** 反编译实证（solmaid 1.0.1，Forge 与
NeoForge 两份 jar 同款）：

- solmaid **从不修改蛋糕**：没有 mixin（`solmaid.mixins.json` 的 mixins 列表为空）、
  不动 `Items.CAKE` 的食物属性/FOOD 组件。旧探测（1.20.1 看
  `Items.CAKE.m_41473_/m_41472_`；1.21.1 看 FOOD 组件）读到的一直是"没人改过蛋糕"，
  于是按配置开启——探测在这一侧的结论是对的。
- 真正的机制：solmaid 的 `MaidFeeding` 监听 `PlayerInteractEvent.EntityInteract`，
  对**任意**女仆（含野生未驯服的）只要主手物品**有食物属性**就把 1 件塞进她手里、
  触发进食、回血，然后 **setCanceled(true)**。TLM 的驯服链是原版
  `EntityMaid.m_6071_ → tameMaid(stack)`（蛋糕正是 `getTamedItem`）——事件被取消后
  这条链整段走不到。**我们让蛋糕可食用（1.20.1 mixin / 1.21.1 FOOD 组件）正好武装了
  它**：没有我们的可食用化，蛋糕没有食物属性，solmaid 对它视而不见、驯服照常。

也就是说这是与"别的模组改过蛋糕"**不同的另一类冲突**：「别的模组会把可食用物品判定为
投喂目标，从而抢走野生女仆的驯服交互」——配置注释里其实写过这句，只是启动探测没覆盖它。

## 改动（两树镜像，`MaidCakeEatHandler`）

1. **启动探测扩到「喂食抢答型」附属**：新增 `FEED_HIJACKER_MODS = {"solmaid"}`；
   `detectExternalCakeModification`（1.20.1，FMLCommonSetup）与
   `onModifyDefaultComponents`/`verifyAfterLoad`（1.21.1）先查
   `ModList.isLoaded`，命中即 `disableBecauseConflict("喂食型附属 solmaid…")`
   ——配置面板同步显示为关、日志一行写明原因。1.21.1 侧在组件事件就让出，FOOD 根本不挂。
2. **运行时哨兵（兜底名单外的同类附属）**：新增 `@SubscribeEvent(priority = LOWEST)`
   的 `onInteractGuard`——所有监听跑完、原版交互之前，看「**野生**女仆 + 我们的蛋糕」
   的交互是否被**别的模组**取消、且她手里被塞了蛋糕/正在吃（抢答的特征；保护类取消
   不会动她的手）→ 当场自动关闭。第一个被抢的蛋糕收不回来，之后恢复正常。
3. **自己的取消让路**：`onInteract`（投喂自己的女仆）开头加 `if (event.isCanceled())
   return;`，并记 `weCanceledThisEvent` 给哨兵排除自己——防"对方喂一份、我们再喂一份"
   的双喂（一次点击亏两块蛋糕）。
4. 配置注释与面板文案补充说明新探测口径。

## 验证

- Neo 编译 `NEO_OK`；Forge 编译 `FORGE_OK`；14 护栏 `FAIL 0 / WARN 0`。
- 四套专服回归 PASS（1201 / neoforge1211 × server / maid_load）。
- **针对性实测**（1201 测试服临时放入 solmaid-1.0.1）：
  - 带 solmaid：`[蛋糕可食用] 检测到冲突（喂食型附属 solmaid（生活调味料：酒狐萝贝）会把
    可食用物品直接喂给女仆并抢走交互，蛋糕驯服会被截走），本模组的蛋糕功能已自动关闭…`，
    且 `promaid-common.toml` 落盘 `cakeEdible = false`；
  - 移除 solmaid、恢复配置后：`未检测到其他模组修改蛋糕，按配置开启`。
  测试服已还原（solmaid 已移除、配置复位）。
- Neo jar：18,221,267 字节，SHA256
  `7571A57C14C05431A07DA6EC2FA93B48605A03BB8EAAC2E7F734A94098336D4F`。
- Forge jar：18,160,079 字节，SHA256
  `B539976FCD11E62C6C89AA4F45D7765A71C7A088BBC133BEF49BD210D2B96F4D`。
- 已部署 4 实例，`match=True`。

## 边界

- 「喂食抢答型」名单目前只有 `solmaid`（反编译实证过的这一个）；其他同类附属由运行时
  哨兵兜底（第一次抢答后才让出，第一个蛋糕会被喂掉）。
- 自动关闭是**让出**语义：装着该附属时面板重新打开蛋糕功能，下次启动会再次自动关闭
  （蛋糕驯服会被抢走，刻意不让玩家踩这个坑）。
- 已驯服女仆的投喂（我们的 +10 好感链路）在让出后一并停用——这是同一把开关的既有语义。
- 探测只认"附属在场"与"抢答行为"，不会因为别的模组碰了普通物品就误关。

## 验收

1. **装着 生活调味料：酒狐萝贝 的实例**：启动日志出现"检测到冲突（喂食型附属 solmaid…），
   蛋糕功能已自动关闭"；配置面板「蛋糕可食用」显示为关。
2. **用蛋糕右击野生女仆**：驯服恢复正常（她不再被抢喂）。代价：自己的女仆也不能再用
   蛋糕右击投喂（+10 好感）——移除该附属或在面板手动重开即可恢复。
3. **未装该附属的实例（如龙之冒险）**：行为与之前一字不差（蛋糕功能照常开启）。
