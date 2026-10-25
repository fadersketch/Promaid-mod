# R1135 反向同步：1.20.1 烧制逻辑 / 饰品页 / 航图内存 → 1.21.1 NeoForge

## 排班

- **跑道**：A 版本同步（玩家："在1.21.1修一下文案，而且现在1.21.1女仆的烧制判定又出问题了，
  现在需要反向同步，将1.20.1的烧制逻辑更新和饰品栏等都同步过去"）。
- **范围**：本轮只改 `promaid_src_neo`（Neo 树）；1.20.1 Forge 树保持 R1134 的最终状态不动。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 文案：删掉「暮色森林灰烬熔炉」错误举例 | Neo 四处文本改成可验证口径 | ☑ |
| 2 | R1131：骑乘中不占台 / 绑定即动手 / 营火诊断字段先清空 | 与 Forge 同口径 | ☑ |
| 3 | R1132：站位头格 above / 邻格扫两层 / 碰撞与支撑判定 | 与 Forge 同口径 | ☑ |
| 4 | R1133：逐台匹配 + 专炉优先 + 换台闸 + 无台播报 + 看门狗冷却 | 与 Forge 同口径 | ☑ |
| 5 | R1134：5×5×5 成品清扫跳过绑定台 | `tookOutputThisRound` 能置真 | ☑ |
| 6 | 饰品页：RemoteMaidGui 3 秒宽限 + `MaidGuiPacketRemoteMixin` | 远程女仆配置能切饰品页 | ☑ |
| 7 | 航图客户端内存：书解析缓存 / 标记裁剪 / 静态配色 | 每帧不再解析整本 NBT | ☑ |
| 8 | 编译 / 14 护栏 / 打 jar / 部署 / 两套 Neo 测试 / 文档 | 全绿 | ☑ |

## 改动（仅 `promaid_src_neo`）

### 1. 文案

- `MaidSmartConfig`：字段注释与 TOML `.comment(...)` 删掉「比如暮色森林的灰烬熔炉」，改为
  「即方块本身不是熔炉类、方块实体却继承原版熔炉的那一类」。
- `PromaidConfigScreen`：同一条悬浮提示同步改写（顺带把原先半句「方块不是熔炉类、实体却是」
  补完整）。
- `MaidCookBehavior#isFurnaceFamilyBlock` 的说明改为「方块本身不是熔炉类、方块实体却继承原版熔炉」。

### 2. 烧制：与 1.20.1 R1131–R1134 对齐

- **R1131**
  - `tick` 开头：`maid.getVehicle() != null` 时释放绑定台并直接返回——骑乘中不占台，
    落地后由 start/tick 重新扫描。
  - 绑定成功后 `cooldown = 0`：第一轮立刻处理，不再等最多 5 秒。
  - 从熔炉迁去营火前先清 `nearestLitCampfire` / `nearestAnyCampfire`，避免诊断显示旧值。
- **R1132** `stationStandSpot`
  - 头格由 `below()` 改成 `above()`（旧版方向反了，等于"下方是实心就跳过"）。
  - 邻格改为扫 `dy = 0..1` 两层（齐平的营火、同层全是草地时，空位在这一层之上）。
  - 脚下/头格要求碰撞箱为空；脚下还要有 `isFaceSturdy(UP)` 的支撑面。
- **R1133**
  - `findFurnace` 去掉"背包第一件料决定唯一炉型"的一刀切，改为逐台调用
    `furnaceMatchesInv`；候选按 `stationRank`（烟熏炉/高炉 = 2、熔炉 = 1）加就近排序。
  - 新增 `stationRank` / `currentStationIsBest`（只读，营火按 `findStation` 同口径算最高档）。
  - `processFurnace`：刚收完成品且当前台不是最好 → 本轮不往旧台补料，交给
    `rebindAfterCycle` 换台（日志 `cook round ... switch`）。
  - `furnaceMatchesInv` 的烟熏炉口径收窄为**真有烟熏配方**（`RecipeType.SMOKING`），
    背包 / 手部 / 附属容器三处同步。
  - `notifyNoIngredient` 先做 30 秒节流、再问 `hasUsableStation`（逐台确认配方 + 营火），
    附近真没有能烧的台才播报。
  - `rebindAfterCycle` 记录 `cook rebind: 收完成品 → 重选台 X → Y`。
  - 看门狗判过"60 秒零进展"的炉子写入 `STATION_STUCK_UNTIL`（60 秒内不再选它），
    避免"解绑 → 重扫 → 又挑回同一台"原地打转。
- **R1134** `sweepNearbyOutputs` 跳过 `furnacePos`：绑定台的成品只由 `processFurnace` 收，
  `tookOutputThisRound` 才会置真，"收完成品就换台"才真正生效。

### 3. 饰品页（远程女仆配置）

- **两道门**（javap 实证）：页签包 `OpenMaidGuiPackage.stillValid` 要求玩家在女仆 **5 格**内，
  远程时换页请求被静默丢弃；切页签又会 `removed()` 掉旧容器（我们据此收回授权），
  新容器 `stillValid` 按 Neo 的 **4 格**判定，于是一闪而过。
- **改动**：
  - `RemoteMaidGui` 增加 `RECENT` 与 `SWITCH_GRACE_MS = 3000`：`disable()` 时记入宽限，
    宽限期内 `isActive` 命中就把会话接回（`opened = true`，泵只维持同步、不重复开界面）；
    `prune` 一并清理过期条目。
  - 新增 `MaidGuiPacketRemoteMixin`，在 `OpenMaidGuiPackage.stillValid` 的 `RETURN` 处，
    仅当 `RemoteMaidGui.isActive` 时放行距离；死亡 / 睡眠 / 换主人三条照旧否决。
  - `mixins.promaid.json` 注册新混入；`tools/arch/arch_modules.py` 把新文件登记进
    `promaid.mixin.network-chunk`。
- **命名差异**：1.20.1 的目标类是 `OpenMaidGuiMessage`，NeoForge 1.21.1 是 `OpenMaidGuiPackage`，
  方法名在 Neo 树写官方名 `stillValid`。

### 4. 航图客户端内存（R1134 优化镜像）

- 新增 `bookFor`：按 `ItemStack` 引用 + `CustomData` 组件引用缓存 `PatrolBook`；
  上一帧同一本书不再重新解析整本 NBT。`clear()` / 退出登录时清空缓存。
- 非选中/非标记中的轨道只画 64 格内的标记，每帧总预算 96 颗；选中/标记中那条不受限。
- 四组标记配色提为静态常量，不再每颗 `new float[]`；标签只跟随实际画出来的标记。

## 验证

- Neo 编译：`EXITCODE=0`（500 个源文件）。
- 14 项护栏：全部 `FAIL 0 / WARN 0`；`check_mixin_targets` 已校验注入点 297（下限 200）。
- 架构文档：改了 `arch_modules.py` 后重跑 `tools/arch/build_arch.py`（121 模块 / 61 API / 77 箭头），
  `check_arch` 覆盖回到基线 184。
- 打包：`patched/promaid-1.3.3-neoforge-1.21.1.jar`，**18,270,107 字节**，
  SHA256 `B0B219F2B2EAE4361887442D3CCF88E40478BFD55173DD1F599A797B629D7E55`。
- 部署：4 个实例全部 `match=True`（两个 1.20.1 客户端、1.21.1 客户端、`pack1201` 专服）。
- 专服测试：`test_server.py neoforge1211` → `verdict: PASS`；
  `test_maid_load.py neoforge1211` → `verdict: PASS`（含女仆召唤，新混入加载无报错）。

## 边界

- 只改 Neo 树；1.20.1 Forge 树未动（仍是 R1134 的 18,209,235 字节 / `71C10DA8…`）。
- 两树容器距离不同（Forge 3 格 / Neo 4 格），注释与实现各按本树；页签包都是 5 格。
- 饰品页放行只针对本模组替玩家开的远程会话；手动右键、以及她死亡 / 睡着 / 换主人时按 TLM 原版。
- 航图只改客户端绘制预算，不改变航线数据、巡游逻辑与服务端判定。

## 验收（1.21.1 玩家侧）

1. 烧肉时旁边放烟熏炉：收完成品后应转台（日志 `cook rebind` / `cook round ... switch`）。
2. 背包"先肉后铁"、旁边只有高炉：应去烧铁（逐台匹配），不再站着不吭声。
3. 齐平的营火（周围同层是草地）也应能绑定并站到旁边干活。
4. 排班表远程开「女仆配置」→ 点饰品页签：应停在饰品页，不再闪退。
5. 手持长航图走动：F3 内存曲线平稳，不再持续爬升。
