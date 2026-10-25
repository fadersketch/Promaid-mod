# R1140 双女仆第二只卡死：高炉被当成"能烧食物"的台 + 空转兜底

## 排班

- **跑道**：A 玩法修复（玩家实测："现在单个女仆可以正常工作。但是我刚刚又打开了 1.20.1 的存档，
  两个女仆一起工作的时候，第 2 只女仆被卡死了。她成功执行了寻路，但是接下来没有任何动作。"）。
- **范围**：`promaid_src` 与 `promaid_src_neo` 同步改；版本号仍 **1.3.3**（同号重打）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 从 1.20.1 实机日志里找出"走到了但不动"的那一只 | 一只在营火上正常干活，另一只绑定高炉后每轮 `acted=false busy=false` | ☑ |
| 2 | 解释"为什么绑的是高炉" | 只看配方判据：高炉被算进"能接食物"的熔炉族 | ☑ |
| 3 | 修选台判据（高炉不吃食物） | 高炉只按高炉配方判"有活干"，食物不再算数 | ☑ |
| 4 | 加空转兜底 | 同一台连续 10 秒零动作 → 30 秒冷却换台，不等 60 秒看门狗 | ☑ |
| 5 | 编译 / 14 护栏 / 双 jar / 部署 / 四套专服测试 / 文档 | 全绿 | ☑ |

## 日志实证（1.20.1 Forge，23:09–23:12）

两只女仆同名，日志行交错；按台子坐标可分开。A 只用营火，一切正常；B 的轨迹是：

```
23:10:39.322  cook start: 台=营火 (-417,-60,446)
23:10:39.669  cook chain: step=stand:ok 站位=(-417,-60,445) 距离=0.66
23:10:39.670  cook round: be=Campfire placed=1 porkchop
23:10:45.269  cook chain: step=check:block 台=(-417,-60,446) 方块已不是烧制台 → wander
23:10:45.321  cook chain: step=scan:bind 绑定 炉子 (-417,-60,444) 距离=1.00
23:10:45.322  cook round: be=BlastFurnaceBlockEntity slot0Empty（无料可喂）
23:10:45.322  cook chain: step=round:end acted=false busy=false
23:10:45.324  cook chain: step=reselect:same "现算最好的仍是 (-417,-60,444)"
   …… 同样的 round → round:end(acted=false busy=false) → reselect:same
   每 4~6 秒一轮，一直重复到日志结束 23:12:03（约 80 秒，一步没动）
```

同一时刻另一只（营火 `(-416,-60,445)`）持续 `placed=1 chicken / 收成品`，说明不是全局卡死。

B 的 `cook no-feed diag` 背包 dump（节选）：`minecraft:porkchop×114512`、`sculk_catalyst×29`、
`enchanted_golden_apple×141`、`glowstone×114404`、`trials:wind_charge×114497`……**没有任何燃料**，
也没有高炉能烧的矿料。她包里的猪排只能上营火/熔炉/烟熏炉，高炉一件也吃不下。

## 根因

选台/绑定成立性的食物分支（`furnaceMatchesInv`）写的是：

```java
if (isCookFood(stack)) {
    return bs instanceof SmokerBlock ? hasRecipe(SMOKING) : isFurnaceLikeBlock(...);
}
```

而 `isFurnaceLikeBlock` 的实现是 `SmokerBlock || FurnaceBlock || isFurnaceFamilyBlock(...)`，
`isFurnaceFamilyBlock` 的第一行又是 `bs.getBlock() instanceof AbstractFurnaceBlock`——
**高炉和烟熏炉都继承 `AbstractFurnaceBlock`**。于是"这是一台高炉"走到了 `true`：

- 选台：只要背包里有一块猪排，高炉就永远算"有活干"，被 `findFurnace` 选中；
- 喂料：`extractForFurnaceType` 对高炉只认 `BLASTING` 配方 → 猪排喂不进去；
- 重选：`reconsiderStation` → `findStation` → 同一个 `furnaceMatchesInv` 仍为真 →
  `reselect:same` 算回同一台 → 无限循环。

60 秒看门狗本该兜底，但这只女仆的行为在 23:10:39 / 23:11:28 / 23:11:58 三次
`cook start` 重启，每次都把 `lastProgressGameTime` 重置，并且重启时 `start()` 又用同一套
判据把她绑回高炉——兜底被重启节奏吃掉了。

## 改动

### 1. 高炉不算"能接食物"的台（两树）

`isFurnaceLikeBlock` 先排除 `BlastFurnaceBlock`，再接原有的烟熏炉/熔炉/等价族判据。
高炉能不能开工只由后面那条"高炉配方 + `cookSmeltOres` 开关"决定（口径与喂料侧
`extractForFurnaceType` 完全一致）。烟熏炉、熔炉、等价族模组炉的行为一字不变。

### 2. 空转兜底：同一台 10 秒零动作 → 换台（两树）

新增 `idleSinceGameTime`：

- 一轮 `acted=false` 且 `stationBusy=false`（台上没在烧）→ 开始计时；
- 有任一动作、或台上有东西在烧 → 清零；
- 同一台连续 200 tick（10 秒）零动作 → 记
  `cook idle-giveup: maid=<名> 台=<坐标> 连续 10 秒零动作（喂不进/烧不动） → 30 秒内不再选它`，
  台子进 `STATION_STUCK_UNTIL`（30 秒），收成品、解绑、闲逛重扫；
- 链路日志新增 `step=reselect:idle`；
- 行为重启、新台绑定、换台、放弃台都同步清零。

这样即使以后某个模组配方又让"判据说有活、喂料侧喂不进"两边打架，她也最多站在原地 10 秒，
而不会无限空转。

## 验证

- 两树编译：`EXITCODE=0`。
- 14 项护栏：全部 `FAIL 0 / WARN 0`；`check_comment_style` 为 `PASS`；297 个 mixin 注入点。
- 打包：
  - `patched/promaid-1.3.3-forge-1.20.1.jar`，**18,212,585 字节**，
    SHA256 `450ECB38621453F098F10211ECB96F8D21423310BF498F07C72A659B8CF4A936`。
  - `patched/promaid-1.3.3-neoforge-1.21.1.jar`，**18,273,556 字节**，
    SHA256 `C2BACC452C5A93691B2B1212E2C4F0861DA1BA8A0276FDD855A39A36F351CDC9`。
- 部署：4 个实例全部 `match=True`。
- 专服测试：`test_server` / `test_maid_load` × `1201` / `neoforge1211` 全部 `verdict: PASS`。

## 边界

- 高炉本身功能不变：有粗铁/矿石（带高炉配方）时照常选它、照常烧；只是"食物"不再能证明
  高炉有活干。
- 烟熏炉仍只认烟熏配方，熔炉仍通吃可烧制物；等价族（`cookFurnaceFamily`）判据不变。
- 空转兜底只在"台上没在烧"时计时；正常烧制（槽 0 有料在烧）不会被误判。
- 营火优先与运行中的台不打断（R1137）不变；放弃的台 30 秒冷却只影响她自己。

## 验收

1. 1.20.1 双女仆、其中一只只有猪排：她应绑营火（或烟熏炉/熔炉），不再去高炉前站桩；
   若旧存档里已绑着高炉，下一 tick 会走 `step=check:inv ... 解绑重选`。
2. 附近只有高炉、包里只有食物：她不绑台，日志 `scan:none` + `cook scan` 说明没有可用台，
   随后闲逛（不会站着不动）。
3. 有粗铁 + 高炉：与之前一致，正常绑定、喂料、收成品。
4. 任何"喂不进"的意外组合：10 秒内出现 `cook idle-giveup` 与 `step=reselect:idle`，
   随后换台；不会无限重复 `reselect:same`。
