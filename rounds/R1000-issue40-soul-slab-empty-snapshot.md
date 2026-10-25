# 实测一千〇七 自动回魂符生成空快照（issue #40）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）
- **跑道**：A 修 bug —— 实机崩溃栈 + 存档物品 NBT 证据齐全，不是功能。
- **开工前读过**：`ARCHITECTURE-SWITCHES.md`（无"回收符/魂符"相关开关）、`DEBT.md`（无等价条目）、`rounds/RELEASES.tsv`（当前 1.3.3 第 139 次重打）、`R999` 记录 —— 结论：与"金色描边"那轮无关，是 `MaidSoulSpellGuard` 自动收符链路自身的问题。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位：复现链路与崩溃栈对上 | `spawnFromStore` → `getUUID("Owner")` NPE，物品 `maid_info={}`（issue 原文/存档证据） | ☑ |
| 1 | 根因 + 同类点 | 反编译实证 TLM 1.21.1 `storeMaidData` 在组件非 null 时静默不写；全仓扫 `SMART_SLAB` 只此一处创建 | ☑ |
| 2 | 两树改 + 编译 | `EXITCODE=0` | ☑ |
| 3 | 打包装机 | 4 个目标与 `patched/` 同字节（match=True） | ☑ |
| 4 | 交开发者验收 | 开发者实测后给出结论 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = 带自动回魂符打死女仆 → 看 `logs/promaid.log` 的 `[promaid/回魂符]`；若背包里还留着旧废符，进世界 5 秒内应收到"已还原为空魂符"并看到空符替换。

## 需求 / 来源
- 来源：GitHub issue #40（`rodericksthescriptkid`，MC 1.21.1 / NeoForge 21.1.250 / TLM 1.5.3 / Promaid 1.3.2 本地测试构建）。
- 一句话需求：自动回魂符不能产出"快照为空"的魂符；释放这种符会让客户端 NPE 崩溃，必须从写入链路根治，并给已经坏掉的符一条安全回收路径。

## 评估
- **跑道**：A（崩溃 + 物品无法释放）。
- 级别：**P1**（触发条件窄，但后果是客户端崩溃 + 女仆数据不可恢复）。
- 证据：
  - 崩溃栈：`NbtUtils.loadUUID` ← `CompoundTag.getUUID` ← `AbstractStoreMaidItem.spawnFromStore` ← `ItemSmartSlab.useOn`（issue 原文）。
  - 物品 NBT：`touhou_little_maid:maid_info = {}`，`MaidInfo.Owner` 不存在；自定义数据里 Promaid 的自动标记/冷却/名字都在。
  - javap（TLM 1.5.3 neo）：`storeMaidData` 第一件事是 `stack.get(MAID_INFO)`，**非 null 直接 return**；新 `ItemStack` 无默认组件（`ItemSmartSlab` 构造器实证），所以正常路径能写——但一旦物品带着"已存在的空组件"进来，它就静默跳过，产出空快照。1.20.1 的 `storeMaidData`（`original_tlm.jar`）没有这个提前返回，故 1.21.1 侧风险最高；两树仍同修。
- 根因三问：
  - **复现**：自动收符链路把 `storeMaidData` 的"写没写进去"当成必然；物品若已带空组件 → 跳过 → `maid_info={}` → 释放必 NPE。
  - **根因**：把第三方 API 的"静默 no-op"当成成功，写入后没有校验。
  - **同类点**：全仓扫 `SMART_SLAB_HAS_MAID` / `storeMaidData` / `MAID_INFO`，创建魂符的只有 `MaidSoulSpellGuard` 一处；`IndexStoneInterruptHandler`、`RideBindManager` 只在 ToItem 里清自己的键，不清快照。

## 改法
- **写入端（两树）**：不再调用 `storeMaidData`，改为与它同源的三步：`maid.saveWithoutId(snapshot)` → post `MaidAndItemTransformEvent.ToItem`（IndexStone / RideBind / 本类的 ToItem 钩子照常生效）→ 把快照写进魂符组件/根标签。
- **校验闸**：写完当场读回，要求快照非空且 `Owner` 是以 **int 数组**（UUID）存在；不通过就**放弃收符**（`return false`，女仆正常吃这一击、走 TLM 自己的死亡链路），并在 `logs/promaid.log` 留 `[promaid/回魂符] 快照缺失 Owner，放弃收符（防发废符）`。
- **事件顺序与 TLM 对齐**：1.20.1 先 `getOrCreateTagElement("MaidInfo")` + save、再 post ToItem（javap 实证），本实现照抄；1.21.1 是 post → set 组件，同样照抄。第三方在事件里看到的物品状态与本模组不接管时一致。
- **冷却写入顺序**：从"收符前"挪到"校验通过后"——放弃收符时女仆身上不会留下 180 秒冷却。
- **旧废符兜底（两树）**：玩家每 100 tick 扫一次背包（主/副手 + 物品栏）。三个条件同时成立才替换：物品是 `SMART_SLAB_HAS_MAID`、`maid_info` 组件存在、`Owner` 缺失或快照为空。替换成 `SMART_SLAB_EMPTY`，聊天栏提示一次 + `[promaid/回魂符] 清理废符 N 枚…`。
- 被否方案：
  - *只调换 `storeMaidData` 调用顺序* —— 挡不住"组件已存在"的输入，治标不治本。
  - *客户端 mixin 拦 `spawnFromStore`* —— 需要注入第三方类、版本漂移风险高；且只能防崩，不能回收。改为服务端把废符换成空符，点击前就已经安全。
  - *自动删除废符* —— 不如换回空魂符：玩家本来消耗的就是一枚空符，换回空符是等价的补偿。

## 触及文件
- `promaid_src/com/maidsmart/combat/MaidSoulSpellGuard.java`（写入端 + 校验 + 清扫）
- `promaid_src_neo/com/maidsmart/combat/MaidSoulSpellGuard.java`（镜像）
- `promaid_src/com/maidsmart/ProMaidExtension.java`、`promaid_src_neo/com/maidsmart/ProMaidExtension.java`（玩家 tick 接清扫）
- 无新配置项 / 无新 i18n key / 无新 mixin。

## 验证
- 两树编译：`cmd /c tools\build\compile_promaid.bat` / `compile_neo.bat` —— `EXITCODE=0`。
- 14 护栏：`FAIL 0`（`check_comment_style` 首轮因类注释多 1 行报 `LONG-BLOCK`，压回 15 行后 PASS）。
- 打包：`build_promaid.py` / `build_promaid_neo.py` 均 `BUILT`；`patched/` 现字节：
  - forge `ac25b060…1068`（17936128 字节；首建 `9c5a6aed…7ae0` 因 ToItem 顺序微调作废）
  - neoforge `8e9c204e…3ef1`（17983802 字节）
- 装机：`deploy_all.py` 4 个目标 `match=True`（1.21.1 Neo / 1.20.1 Forge / 龙之冒险 / pack1201）。
- 专服回归：`test_server.py 1201` PASS、`test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS。Forge 末版（`ac25b060…1068`）重打后复跑 `test_server.py 1201` 仍 PASS；Neo 末版哈希与回归时一致（`8e9c204e…3ef1`），两次结果对应现字节。
- **反证**：旧写法对"组件已存在的空符"是 `get(...) != null → 直接 return`（javap 偏移 11-12/64），必然留下 `maid_info={}`；新写法无条件 `set(MAID_INFO, CustomData.of(snapshot))` 并读回校验，空快照/缺 Owner 都会在发符前中止。实机反证待开发者按上面的验法复测。
- 未做的验证：未在开发者存档里复现原始触发条件（存档未提供）；未跑客户端图形界面（本轮只动服务端链路）。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）
- 状态：`未验收`。
- 未验收之前：不写 changelog、不写 release 说明、不发平台。

## 边界（没动什么）
- 没有改 TLM 的 `storeMaidData` / `spawnFromStore`，也没有加客户端 mixin。
- 没有改 TLM 自己的手动收符路径（`SlabClickEvent`）：它仍走 `storeMaidData`，理论上同样可能被"预先存在的空组件"坑到；本模组的废符清扫会把这类符兜底还原成空符。
- 没有改 `MaidAutoResurrect`（墓碑/自动复活）与 IndexStone / RideBind 的 ToItem 清洗逻辑。
- 没有新增配置项，"废符清扫"始终开启（它只动三个条件同时命中的、已证明不可释放的物品）。
- issue 原文提到的"女仆仍在继续战斗"未在本轮归因：代码路径里 `maid.discard()` 在提示之后立即执行，服务端不存在"提示了却还活着"的分支；更可能是观察窗口内的客户端表现或多女仆场景，待开发者复核。

## 台账
- 清掉的债：无对应 `BD-` 条目（首次报告即在本轮闭环）。
- 新增的债：无。
- 配额：本轮为 A 类修 bug 轮，不触发功能轮配额。
