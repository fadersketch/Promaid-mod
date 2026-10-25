# R1107 营火四修：成品进附属容器、满火也能点、手里的肉能烤、点火用主手

## 排班

- **跑道**：C 功能（两树镜像）。
- **开工前读过**：R1105/R1106 的营火链路（`scanCampfire` / `processCampfire` / `tryLightCampfire`）、
  `MaidExtraContainer` 的 `contains/pull/overflow` 口径、1.21.1 `CampfireBlockEntity.placeFood`
  的消耗实现（`consumeAndReturn(1)` 会改写传入栈）、实例日志里的
  `cook round be=Campfire placed=0 minecraft:air` 与背包 6/6 满的快照。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 烤好的成品塞不下背包时继续塞附属容器 | 背包满→`overflow`→仍满才留地 | ☑ |
| 1 | 熄灭的营火不再被"四位全满"挡在绑定之外 | 空位闸只对已点着的火生效 | ☑ |
| 2 | 玩家塞到手里的生肉也能烤 | 手部食材进背包；背包满则直接从手摆 | ☑ |
| 3 | 点火换到主手 | `equipIgniterToMainHand`，扣耐久也记主手 | ☑ |
| 4 | 日志 `placed=` 不再误报 0 | 摆之前先拷一份用于日志 | ☑ |
| 5 | 编译/护栏/打包/部署/测试 | 两树 OK；护栏全绿；四套 PASS；4 实例 match=True | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

"1.女仆用营火烧出来的肉会掉出来。而不是回到女仆背包/额外容器。"
"2.如果一个熄灭营火上面已经排满了肉，那么女仆不会去绑定及点燃这个营火。熄灭的营火不应在绑定的黑名单"
"3.女仆不会烤牛肉。"
"4.点火时改为把物品换副手换成换到主手，因为动的是主手。"

## 评估

四条各自独立，但根因都落在"她只认背包"与"空位闸不分点着与否"这两处：

1. 收成品只往 `getAvailableBackpackInv()` 里塞，塞不下就 `continue`——掉落物留在火边，
   玩家看到的就是"肉掉出来了"。实例快照正好是背包 6/6 满。
2. 选台的空位闸对**所有**营火生效。熄灭的火上已经排满四块肉时 `campfireHasFreeSlot`
   为假，她连绑都不绑，自然也没有机会去点亮。
3. 玩家把牛肉塞进她手里（主手/副手）时，`hasCampfireFood` 与摆料循环都只看背包，
   手部食材等于不存在；背包满时 `stashHandFood` 也搬不进去。
4. 点燃动作实际作用在主手，道具却在副手——视觉与逻辑不一致，且 `hurtAndBreak` 记的是副手槽。

## 改动（两树镜像，`MaidCookBehavior`）

1. **成品溢出到附属容器**：收掉落物先 `insertItemStacked` 进背包，仍有余量再
   `MaidExtraContainer.overflow`（精妙背包/旅行者背包等，受 `misc.backpackOverflow` 管）。
   两边都塞不下才把余量留在地上；只要塞进去一部分就按原版口径收掉/改写掉落物。
2. **空位闸只卡已点着的火**：`needFreeSlot && lit && !campfireHasFreeSlot(...)`。
   熄灭的火无论几位全满都可以入选——满火更需要先点着，点亮后原版弹出成品腾位，下一轮自然接着摆。
3. **手部食材两条路**：
   - `hasCampfireFood` 先看双手，再看背包，最后看附属容器；
   - `start`/`tick` 开头 `stashHandFood`：把她手里的营火食材/可烧食材整体搬进背包
     （武器、工具、装备不动），玩家刚塞的牛肉下一 tick 就变成"背包里有食材"；
   - 背包满、搬不进去时，`processCampfire` 在背包摆放失败后走 `placeFromHands`，
     直接从手部槽取一件摆火（四位全满则等下一轮）。
4. **点火换主手**：`equipIgniterToMainHand` 把打火石/火焰弹整栈换到
   `EquipmentSlot.MAINHAND`，原主手物放进腾空的背包格；打火石 `hurtAndBreak(1, maid, MAINHAND)`、
   火焰弹 `shrink(1)`，随后 `swing(MAIN_HAND)`。
5. **诊断修正**：`placeFood` 会消耗传入栈，旧日志 `placed=` 打的是被吃空后的栈，
   于是出现 `placed=0 minecraft:air` 的假象；现在摆之前先 `copy()`，日志记录真实摆上的那件。

## 验证

- Neo 编译 `NEO_OK`；Forge 编译 `FORGE_OK`。
- 护栏全绿：`check_mixin_targets / preset / workflow / catch_swallow / i18n_keys / dev_voice /
  comment_dup / reflection / arch / guide_i18n` 均 `FAIL 0 / WARN 0`，`check_comment_style: RESULT: PASS`。
- 四套专服回归 PASS（1201 / neoforge1211 × server / maid_load）。
- Neo jar：18,227,945 字节，SHA256
  `E7FBBDEDA1ED85CACC565058DF137ECF5519A624F37C7ADE921A98FE1B0820F1`。
- Forge jar：18,166,863 字节，SHA256
  `0B323986C4752AAD6CDE719C149AE10DA1D86A0CF3770E36F01EB3ECBC3C416A`。
- 已部署 4 实例，`match=True`。

## 边界

- 背包与附属容器都满时，成品仍然只能留在地上——没有可以吞掉它的容器，不做销毁。
- `stashHandFood` 会把她手里的食材搬进背包（含玩家刚递过去的）；武器/工具/装备、以及不参与
  烧制的物品不动。这一步对营火与熔炉两条链路都生效。
- `placeFromHands` 只负责营火；熔炉的燃料/食材分槽与烧制类型判断更复杂，手部食材仍走
  `stashHandFood` 进背包这一条路（背包满时熔炉暂时烤不了手里那份）。
- 熄灭的满火可以绑定，但点亮后仍需等原版弹出成品腾出位置，才会接着摆下一件。
- 主手换入的是**整栈**点燃道具；原主手物回到腾出的背包格，不会凭空多出一格。

## 验收

1. **背包塞满 + 精妙背包有空位**：营火烤好的肉不再堆在火边，日志
   `cook round … campfire …` 正常，打开精妙背包能看到成品；地上没有残留。
2. **熄灭的营火 + 四位已排满**：她会绑上并走到旁边点亮（`cook ignite: …`），
   不是绕开；点亮后成品弹出，她收了之后继续摆。
3. **把生牛肉直接塞到她主手**：下一 tick 内牛肉进背包（或背包满时直接从手摆上火），
   日志出现 `placed=minecraft:beef`，烤完收进背包/附属容器。
4. **点火动作**：界面里打火石出现在主手，原主手物回到背包；打火石扣 1 耐久、火焰弹少 1 个。
