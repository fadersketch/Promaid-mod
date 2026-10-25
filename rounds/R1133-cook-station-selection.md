# R1133 烧制选台：按台逐个匹配 + 专炉优先 + 收完成品后换台 + 没台能烧就播报（仅 1.20.1）

## 排班

- **跑道**：C 缺陷修复（玩家实测："女仆使用炉子似乎还是老版本的用法，不会在收完成品以后解绑一次
  以实时绑定最好的烧制容器……如果女仆物品栏里面先是肉后是铁，旁边只有高炉，那女仆就不会使用高炉，
  也不会播报语音"）。
- **范围**：1.21.1 玩家已确认无问题，按指示不重建、不改动 Neo 树。
- **开工前读过**：两树 `MaidCookBehavior` 的 `findFurnace` / `findStation` / `pickFurnaceKind` /
  `furnaceMatchesInv` / `processFurnace` / `rebindAfterCycle` / `notifyNoIngredient`、
  `extractForFurnaceType`、语音 `manifest.json`（cook_no_fuel / cook_no_food）、
  `MaidSmartConfig` 的 cook 开关默认值。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 两树逐方法对比（含归一化结构对比） | 上述方法逻辑一致，仅 SRG/Mojmap 命名差异 | ☑ |
| 2 | 复现"只有高炉 + 背包先肉后铁" | `pickFurnaceKind` 第一件肉即返回 FOOD → 高炉被整类否决 | ☑ |
| 3 | 选台改为逐台匹配 + 专炉优先 | `findFurnace` 用 `furnaceMatchesInv` 逐台判定，烟熏炉/高炉 > 熔炉，同级就近 | ☑ |
| 4 | 收完成品后先换台再补料 | 收完那轮若当前台不是最好 → 不补料，交给 `rebindAfterCycle` 换台 | ☑ |
| 5 | 没台能烧时播报 | `notifyNoIngredient` 改问"附近有没有能烧她的料的台"，复用 cook_no_food 语音 | ☑ |
| 6 | Forge 编译/护栏/打包/部署/两测试/轮次文档 | 全绿 | ☑ |

## 取证与两树对比结论

- 玩家描述的三件事（不收完就换台 / 先肉后铁时不认高炉 / 不播报）都能在同一段选台代码里闭环解释，
  见下节根因。
- **两树对比**：把 `findFurnace`、`findStation`、`rebindAfterCycle`、`furnaceMatchesInv`、
  `pickFurnaceKind`、`processFurnace`、`notifyNoIngredient`、`stationBusy`、`extractForFurnaceType`
  等按方法体做归一化结构对比（抹掉 SRG/Mojmap 命名差异），**逻辑一致、无迁移缺失**；
  `rebindAfterCycle`、`findStation` 连结构都逐行相同。也就是说这不是"1.21.1 有、1.20.1 漏了"的
  迁移 bug，而是两边共用的选台口径本身的缺陷（1.21.1 未在这组场景里复现/被接受，按玩家指示不动）。

## 根因（同一段选台代码上的四处）

1. **先扫背包定唯一炉型**：`pickFurnaceKind` 按背包顺序扫，遇到第一件食物就 `return FOOD`，
   `findFurnace` 再拿这个 `kind` 否决台子（高炉要求 `kind == ORE`）。背包里肉在前 → 高炉被整类
   跳过；玩家的铁就在包里，却一台也不绑。
2. **"有料"当"能烧"**：`notifyNoIngredient` 只要 `pickFurnaceKind != NONE` 就静默。于是"有肉但
   附近只有高炉"= 有料、换炉中 → 永远不播报，玩家看到的是"站着不动也不吭声"。
3. **不是"最好的台"**：`findFurnace` 返回扫描顺序里第一台匹配的方块（dy→dx→dz），不是按炉型
   优先也不是就近。旁边同时有烟熏炉和熔炉时，谁先被扫到就用谁。
4. **收完成品后立刻补料，把换台判据挤掉**：`processFurnace` 同一轮里"收成品 → 补燃料 → 补料"，
   进料槽刚被填上，外层 `tookOutputThisRound && !stationBusy()` 里的 `stationBusy` 就为真 →
   `rebindAfterCycle` 只在"料全烧完"那一刻才可能发生。玩家新放的好炉子要等整批烧完才认。

## 改动（仅 Forge 1.20.1，`MaidCookBehavior`）

- `findFurnace`：去掉"全体共用 kind"的否决，改成**逐台**问 `furnaceMatchesInv`（这台炉子按它自己的
  炉型/配方能不能吃下她身上某件料）；候选之间按 `stationRank` 排序（烟熏炉/高炉 = 2，熔炉含等价族 = 1），
  同级取最近。工作区、开关（`cookSmokerBlast` / 炉子等价族）、其它女仆占用三条过滤原样保留。
- 新增 `stationRank(BlockState)`：台型优先档（专炉优先、熔炉兜底）。
- 新增 `currentStationIsBest(level, maid)`：当前台是否就是"现算出来的最好那台"（营火按
  `findStation` 同一套口径算最高档）。只读。
- `processFurnace` 补料前加一道闸：**本轮刚收完成品**且当前台不是最好 → 这一轮不往旧台补料，
  留给外层"收完成品 → 解绑重选"把台换掉（日志 `cook round ... switch`）。判据只在收完那一轮成立，
  所以不会饿着不喂，也绝不会把已经喂进去的料丢在旧台里（闸在取料/放料之前）。
- `furnaceMatchesInv` 的烟熏炉口径收窄为**真有烟熏配方**（`extractForFurnaceType` 本来就只喂这个，
  旧版"烟熏或熔炉配方任一"会让只在熔炉里能熟的料占着烟熏炉干坐）；背包、手部槽、附属容器三处同口径。
- `notifyNoIngredient`：先看 30 秒播报冷却（避免重复扫描），再问新增的只读探测
  `hasUsableStation`（先 `pickFurnaceKind` 快速否决"根本没料"，再查营火/三种炉子），
  **附近一台能烧的台都没有才播报**；文案沿用既有 `没有能烧的原料了…主人，给我一点生食或矿石吧？`
  （manifest 里已绑 `cook_no_food.ogg`，不新增台词、不会出现"有字无声"）。
- `rebindAfterCycle` 补一条 `cook rebind: maid=... 收完成品 → 重选台 旧 → 新`（换台/没台时才写，
  同台重绑不刷屏）。
- 看门狗新增一条**滞留台冷却**：被判"60 秒零进展"的炉子写入 `STATION_STUCK_UNTIL`，60 秒内不再
  被 `findFurnace` 选中。原因：新排序把专炉放在最前，万一那台够不着（墙后/不可达），
  "看门狗解绑 → 重扫 → 又挑回同一台"会原地打转；冷却后自动恢复候选。
- **1.21.1 树未改动。**

## 验证

- Forge 编译 `EXITCODE=0`。
- 14 项护栏 `FAIL 0 / WARN 0`。
- 打包 + 部署 4 实例 `match=True`（Neo 包未重建，字节未变）。
- Forge 两套专服回归 PASS：`test_server 1201`、`test_maid_load 1201`。
- Forge jar：18,207,365 字节，SHA256
  `B002713C4903970F9337F7BD1A7BC275144E2B76DCE3542B3798F0D62580E1A7`。
- **反证**：
  - 营火仍是最高档（`findStation` 先扫营火，`currentStationIsBest` 也把它算在炉子之上，满员/无空位
    时不抢）。
  - `cookSmeltOres` 关掉时高炉仍不是候选（`furnaceMatchesInv` 的高炉分支原样保留那道开关）。
  - 只喂料/补柴（没收成品）的轮次完全没有新闸，行为与上一版一致。
  - 收完成品后若当前台就是最好的（含唯一的熔炉），照旧立刻补料，不多走一步。
  - 看门狗、解绑前收成品、附近成品清扫、站位判定（R1132）、骑乘让位（R1131）一律未动。

## 边界

- "最好"的排序是 **营火 > 烟熏炉/高炉 > 熔炉**，同类里取最近；同类专炉之间（烟熏炉 vs 高炉）按
  距离取近，不做"先烧哪一型料"的推断。
- 换台只在"刚收完成品且旧台进料槽已空"时发生；正在烧的那一件永远不打断，成品不会落在旧台里。
- 看门狗判过的台有 60 秒冷却（够不着/烧不动的台不会被立刻重扫回来）；营火走它自己的 30 秒
  未点亮冷却，两套互不干扰。
- 专炉只认自己那一型：烟熏炉只吃真有烟熏配方的料，高炉只吃真有高炉配方的料（受开关约束），
  其余一律交给熔炉。
- "没台能烧"时播报的是既有那句"给我一点生食或矿石吧"（唯一已配音的烧制缺料台词）；她手上可能
  确实拿着料、只是附近没有对应炉子——文案不改是为了不凭空多出一条无声台词。
- 版本号仍为 **1.3.3**，同号重打；发布素材等验收后再写。

## 验收

1. 背包先放一组生肉、后面放铁，旁边**只放高炉**（`cookSmeltOres` 开）：她绑上高炉、`cook round ...
   fed fedSlot0=1xiron_ingot`（或矿石）；铁烧完只剩肉时，出现一次 `cook round ... nofeed` 并播
   `cook_no_food` 语音，然后去找熔炉/烟熏炉。
2. 让她在熔炉里烧肉，中途在熔炉旁补一座**烟熏炉**：当前这块肉收完那一轮出现 `cook rebind: ...
   重选台 ... → 烟熏炉坐标`，她走过去改用烟熏炉（不再等整组肉烧完）。
3. 只放一座普通熔炉：与上一版一致，一直用它烧到没料。
4. 有营火且点着、有空位：仍优先营火（不因新排序被炉子抢走）。
5. 背包完全没有可烧物：30 秒最多播报一次 `cook_no_food`（与上一版相同）。
6. 日志检索：`cook rebind`、`cook round ... switch`、`cook round ... fed`、`cook round ... nofeed`。
