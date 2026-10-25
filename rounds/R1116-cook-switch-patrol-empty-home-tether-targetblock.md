# R1116 烧制换炉 / 航图空页秒退 / 目标行重叠 / 直升机 home 巡逻 / 未起飞拴绳飞过去 / 目标方块红色实心

## 排班

- **跑道**：C 功能修复（六条玩家实测反馈，全部是既有功能的边界修正，没有新开关）。
- **开工前读过**：`MaidCookBehavior`（`furnaceMatchesInv`/`pickFurnaceKind`/`extract*`/
  `processFurnace`/`campfireStillWorth`）、`PatrolChartScreen.accept/renderTargets`、
  `PatrolFlight.effective`、`RideBindManager` 的 home 闸（R1114 的 `patrolFlight` 旁路）、
  `WorkAreaClamp.homeAnchor/circleCenter`、TLM `MaidConfigManager.setHomeModeEnable` 字节码、
  `GunnerTetherManager`（`isTethered`/`isHanging`/`hangingPlayer`/`modeKind`）、
  `PatrolNetworking.findEligibleMaid`、`PatrolPreviewClient`、R1113/R1114 轮次文档。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | ① 复现"烧完肉播语音然后站住不换炉" | 定位到"烟熏炉把矿石算作通用可烧物"这一假匹配 | ☑ |
| 1 | ① 解绑 + 换炉 + 播报条件 | 没料立即解绑；只有"所有炉型都无料"才播报；气泡文案区分原料/燃料 | ☑ |
| 2 | ② 空目标点页不再被刷新包踢回 | 0.5 秒刷新时留在目标点页/管理页 | ☑ |
| 3 | ③ 目标行文字不压按钮 | 按像素宽截断（含 `[异界]`）且保留颜色码 | ☑ |
| 4 | ④ 直升机 home 沿航迹飞 | 只开 home、未画限制圈时航迹也生效 | ☑ |
| 5 | ⑤ 拴绳未起飞也有「飞过去」 | 牵绳档（`link.leash`）也算同行资格 | ☑ |
| 6 | ⑥ 目标方块红色实心 | 目标点所在整格半透明红，菱形仍在上面 | ☑ |
| 7 | 两树编译/14 护栏/双 jar/部署/四测试/轮次文档 | 全绿 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者，见文末「验收」。

## 需求 / 来源

> 「1.如果女仆发现没有这个炉子/营火可烧的物品（不是燃料），应该立即解绑。然后检索包/手/
> 额外容器内是否还有烧制满足的物品，有的话不报语音。现有链路会导致女仆失去了原本换炉子的能力
> 2.巡逻航图，当内部没有目标点时，玩家只会在那个界面停留零点几秒就被强制退出
> 3.如图，字段重叠在了按键上
> 4.女仆开直升机并处于home模式不会沿着轨道走，疑似被原本的home让直升机悬停掐死了
> 5.绑住未起飞的空袭女仆压根没有飞过去的按键
> 6.目标点所在方块加一层红色实心渲染，让目标点所在位置更明显」

补充示例（①）：

> 「女仆手中有铁矿和生肉，上个版本女仆在烧完肉后会切炉子烧矿，但现在烧完肉就播语音，
> 然后就站那不会换了。而且当前播报文本存在歧义，玩家分不清是"可烧制物"是不是燃料。」

## 评估

### ① 烧制：为什么她烧完肉就不换炉了

- 绑定校验 `furnaceMatchesInv` 的"通用可烧物"分支只问 `smeltAnyAllowed() && isSmeltable(...)`，
  **没有排除烟熏炉**。手里只剩铁矿时：烟熏炉的配方取料 `extractForFurnaceType` 取不出
  （烟熏只认食物），但绑定校验被"铁矿也是熔炉可烧物"判成**仍然匹配** → 她不解绑。
- 槽 0 空 + 这型炉子没料 → `processFurnace` 的 `nofeed` 分支旧版**只播语音、不解绑**，
  于是她抱着烟熏炉站住，永远不会去高炉。
- 播报文本"背包里没有能烧的东西了"里的"东西"既可能被读成原料、也可能被读成燃料，新玩家
  分不清；本轮改成明确说"原料（不是燃料）"。
- 另外"取料"只扫背包与精妙背包，**没扫主/副手**——玩家把最后一块生肉拿在手上时她判"没料"。

### ② 目标点空页秒退

- 界面每 0.5 秒收一版刷新；旧 `accept` 里有一条"包里没有目标点 → `view = VIEW_LIST`"的
  强制回退，于是空目标点页刚打开就被下一拍刷新踢回列表（看起来"停留零点几秒"）。
- 目标点页/管理页本来就是合法空页（可以在这里点「标记目标点」），不该被数据条数决定去留。

### ③ 目标行文字压按钮

- 目标行从 `cx - 158` 起画，右侧「管理」按钮从 `cx - 20` 起（宽 46），文字可用宽度只有
  132px；旧版按**字符数**截断（9 个汉字 + 坐标），中文字符远宽于 9px，必然压到按钮。
- 需要按**渲染像素宽**截断，并且保留 `§` 颜色码（不能把 `§c` 截成半个）。

### ④ 直升机 home 不沿航迹

- R1114 已让"她驾驶飞行载具 + 有闭环航迹"越过 home 闸（`patrolFlight`），但该旁路要求
  `PatrolFlight.effective(maid) != null`；`effective` 旧判据里有一条
  `WorkAreaClamp.homeAnchor(maid) == null → 不生效`。
- `homeAnchor` 要求限制圈心有效（圈心为零还要退回排班锚点）。反编译 TLM
  `MaidConfigManager.setHomeModeEnable(boolean)` 实证：它**只写** `DATA_HOME_MODE`，
  **不设置限制中心**——玩家在 GUI 里只打开 home、没拿罗盘画圈时 `getRestrictCenter()`
  仍是 `BlockPos.ZERO`，于是 `homeAnchor == null`、航迹永远不生效，落回
  `RideBindManager` 的 home 悬停分支（`MaidAirCombat.holdHere`）。
- 航迹自己带目的地，根本不需要圈心；true 的判据就是"home 开关开着"。

### ⑤ 未起飞的空袭女仆没有「飞过去」

- `findEligibleMaid` 用 `GunnerTetherManager.hangingPlayer(maid) == player` 判"是否载着玩家"；
  而 `hangingPlayer` 刻意排除了**牵绳档**（`link.leash == true`，即绳已绑、玩家还没被吊到
  空中）。空袭女仆没起飞时永远处在这一档 → 资格恒为 false → 界面不出现按钮。
- 牵绳档正是玩家要的"绑住她、再点飞过去让她起飞拖人"；`isTethered` 与 `isHanging` 的
  既有区分说明这一档本来就该被承认。

### ⑥ 目标方块红色实心

- 目标点只有一颗半空的菱形 + 竖针，远看/夜里难以判断落点是哪一格；方块本身没有着色。
- 目标点坐标是玩家按中键时的**脚底**位置（站在方块顶面），所以"所在方块"要取
  `floor(y - 0.02)`，否则会落到头顶那格空气。
- `debugQuads` 会写深度：方块若画在菱形之前，会把菱形下半截挡掉；必须后画。

## 改动

### ① 烧制（两树 `MaidCookBehavior`）

- `furnaceMatchesInv`：通用可烧物分支加 `!(block instanceof SmokerBlock)`；烟熏炉/熔炉
  只认 `isCookFood`，高炉只认高炉配方。
- 手部槽与背包同权：新增 `hasInHands`/`extractFromHands`（`getHandsInvWrapper()`），
  `extractCookFood`/`extractByRecipe`/`extractOreFromMaid`/`extractAnySmeltable` 背包取不到时
  从手取；`furnaceMatchesInv`/`pickFurnaceKind` 的四处探针都补手部。
- `processFurnace` 的 `nofeed` 分支：**无条件解绑**（清 `furnacePos`、`stationCampfire`、
  释放占用、解 stationLock、放开移动、闲逛），下一 tick 由 `pickFurnaceKind` 按真正有料的
  炉型重选；**只有 `pickFurnaceKind == NONE` 才播语音**，换炉型时静默。
- 气泡文案：`没有能烧的原料了（不是燃料）…主人，给我一点生食或矿石吧？`
  （`assets/promaid/i18n/en_us.json` 同步英文；`voice/manifest.json`、`tools/voice/gen_voice_pack.py`
  的日文台词同步；`cook_no_food.ogg` 重新合成/归一化并导入两树，75,090 字节）。

### ② 航图空页（两树 `PatrolChartScreen.accept`）

- 删除"无目标点 → 强制回列表"。
- 保留"查看的那条路线被删 → 回列表"（并明确排除 `VIEW_TARGETS`/`VIEW_TARGET_MANAGE`）；
  目标点管理页查看的那颗被删 → 退回目标点列表。

### ③ 目标行截断（两树 `PatrolChartScreen`）

- 新增 `trimToWidth(s, maxW)`：按 `Font.width` 逐字（`§` 两字一组）累加，超宽就截断并补
  `…`；Forge 用 `Font.m_92895_(String)`（`m_92852_` 是 `FormattedText` 重载，编译期已纠正）。
- 目标行文字预算 132px，异界目标保留 `§c[异界]`。

### ④ home 航迹（两树 `PatrolFlight.effective`、`PatrolNetworking.missingFor`）

- `effective`：把 `homeAnchor(maid) == null` 改为 `!maid.isHomeModeEnable()`；圈心不再参与
  生效判据。
- `missingFor`（绑定后的"还差一步"提示）：判据同步——扫帚**或**驾驶飞行载具都算任务就位；
  只提示"开启在家模式"。
- `status` 诊断行同步：`home=` 改报 home 开关，`任务=` 区分「扫帚 / 飞行载具 / 无」，
  不再出现"生效=true 但 home=false"这种自相矛盾的日志。

### ⑤ 未起飞拴绳（两树 `GunnerTetherManager`、`PatrolNetworking`）

- 新增 `tetheredPlayer(maid)`：有链路就返回玩家（含牵绳档），与 `hangingPlayer` 只差这一点。
- `findEligibleMaid` 的拴绳分支改用 `tetheredPlayer`；`modeKind` 仍是
  broom/flight/mount 三档白名单。
- 点「飞过去」后写的 `MaidTravelTarget` 会让飞行链路起飞（牵引/悬停判据本就有
  `aim != null` 的例外），玩家随后被绳拖走。

### ⑥ 目标方块渲染（两树 `PatrolPreviewClient`）

- 新增 `targetBlock(...)`：`floor(x, y-0.02, z)` 起、内缩 0.004、边长 0.992 的立方体，
  顶/底/四侧共 6 个 QUADS 面，红色 `(1.00, 0.24, 0.24)`、alpha 0.30；在目标菱形**之后**
  写入同一份 quad 缓冲。
- 可见性仍沿用"主手/副手拿航图才画"。

## 验证

- 两树编译 `EXITCODE=0`（Forge 494 源 / Neo 499 源）。
- 14 项护栏 `FAIL 0 / WARN 0`（`check_comment_style` 曾对新加 10 行注释块报
  `LONG-BLOCK > 8`，已压到 8 行；`check_arch` 基线 Forge 0 / Neo 184 不变；
  `check_tree_mirror` 仅报告差异、不设门槛）。
- 打包 + 部署 4 实例 `match=True`。
- 四套专服回归 PASS：`test_server 1201`、`test_server neoforge1211`、
  `test_maid_load 1201`、`test_maid_load neoforge1211`。
- Forge jar：18,193,317 字节，SHA256
  `7DA19061B4EC5237E91683F32E7725350968FCE2C2BE276141C28F6824129722`。
- Neo jar：18,254,527 字节，SHA256
  `DBAAFFE8E2002B6678582BAD33B096A6F4B5C1C0D49C1605575B61C316E5AC28`。
- **反证**：
  - ① 烟熏炉 + 仅铁矿：`furnaceMatchesInv` 的通用分支被 `!(SmokerBlock)` 挡掉、烟熏
    只认 `isCookFood` → 判不匹配；`nofeed` 分支先解绑再判 `pickFurnaceKind`，铁矿命中
    `ORE` → 不播语音，下一 tick 重选高炉。旧版这里会播报并站住。
  - ② 空目标点页：`accept` 里不再有任何基于 `targets().isEmpty()` 的 `view` 赋值；
    `VIEW_TARGETS`/`VIEW_TARGET_MANAGE` 显式排除在路线删除回退之外。
  - ④ `setHomeModeEnable` 字节码：只 `entityData.set(DATA_HOME_MODE, flag)`，无
    `restrictTo`；因此"只开 home"必须视为有效。
  - ⑤ `tetheredPlayer` 与 `hangingPlayer` 的唯一差别是 `link.leash` 不提前返回。

## 边界

- ① 只改"绑定校验/取料/解绑/播报"四处；配方判定、四张清单、燃料逻辑、营火链路不变。
  手部取料与背包同口径（仍受 `isSafeToFeed`、装备类、木材黑名单约束）。
- ① 播报仍受 `logRound` 限频（同一炉子同一状态只播一次），不会因为反复解绑刷屏。
- ② 目标点页为空时留在该页；「标记目标点」按钮照常可用。路线被删仍会回列表。
- ③ 只截断显示，不改目标名/坐标数据；截断后仍保留颜色码与省略号。
- ④ home 巡逻只放宽"圈心有效"这一条；非 home 仍不巡逻（跟随主人），跨维度仍不巡逻，
  航迹仍需 ≥3 点且闭环。
- ④ 绑定时"还差一步"的提示文案随之更新，不改绑定结果。
- ⑤ 只放宽"飞过去"的**资格**；点下之后能否飞仍由各飞行链路（空袭/扫帚/载具）自己判定。
  没有绳、或不是自己女仆、或模式不在三档内的仍无按钮。
- ⑥ 只画独立目标点（R1113 的目标点列表），不改变轨道节点菱形；仅手持航图可见；
  别的维度目标仍不画。
- 版本号仍为 **1.3.3**，同号重打；发布素材等验收后再写。

## 验收

1. **烧制换炉**：背包放生肉 + 铁矿，旁边烟熏炉与高炉。让她先烧肉：肉烧完后应**静默**解绑、
   走到高炉烧矿；只有当背包/手/精妙背包里连燃料以外的任何可烧原料都没有时才播
   「没有能烧的原料了（不是燃料）…」。
2. **手上有料**：把最后一块生肉拿在主手、背包空，靠近烟熏炉：她应取走并烧，而不是判"没料"。
3. **航图空目标点页**：打开巡逻航图 → 目标点页（没有目标点）停留超过 5 秒：不再被踢回列表，
   可直接点「标记目标点」。
4. **目标行**：目标名很长 + 坐标 + 异界标记时，文字在「管理」按钮左侧以 `…` 截断，不再压按钮。
5. **直升机 home**：只打开女仆的「在家模式」（不用罗盘画圈），给直升机绑一条 ≥3 点闭环航迹，
   让她驾驶：她应沿航迹飞，不再原地悬停；日志里绑定提示不再要求"圈心"。
6. **未起飞拴绳**：用武装拴绳绑住一个**还在地面**的空袭女仆（未起飞），打开航图目标点页：
   应出现「飞过去」；点击后她起飞并把玩家拖向目标点。
7. **目标方块**：手持航图看向目标点：目标所在整格应有一层半透明红色实心，菱形与文字仍在
   其上方清晰可见。
