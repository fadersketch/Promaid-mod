# 实测九百二十二 工作圈只认 home 模式：非 home（跟随/战斗）一律放行——"旁边就是煤却不挖"的正面修法

> 跑道：**B 功能抢修**——开发者指出上一轮的口径本身就不对：「它明明不处于 home 模式，为什么还会触发相关的判定？很明显是在 home 模式和跟随模式下没做好区分」。本行按这条改定：`WorkAreaClamp.allowsWork` 与 `allows` 同口径——**home 模式**才走 TLM 自己的限制圈，**非 home 一律放行**；R921 那条"她本人在圈外才放行"的启发式由本行**取代**。
> 开发者指令：「有个问题，虽然已经画过的圈牵扯的范围，但它明明不处于 home 模式，为什么还会触发相关的判定？这才是问题吧。很明显是在 home 模式和跟随模式下没做好区分。而且为什么女巫会在那边站着不动，这个问题你也没有解决。」
> 上一轮：`rounds/R921-work-circle-clamp-when-outside.md`（同一处闸的启发式修法——本行取代它）。

## 排班

- **本轮唯一主项**：把"干活选点"的圈闸按模式分开——home 管、跟随不管。驾驶 / 战斗 / 建筑 / 矿表 / 数值一律不动。
- 步骤与完成判据：
  1. **核对"谁负责开 home"**：罗盘与"这块归你管"工具各写什么。判据：能指出"画过圈但没有 home"是不是玩家能造出来的正常状态。
  2. **确认改法不破既有口径**：home 模式那条（TLM 限制圈）与闲逛那条（{@code allowsWander} 的临时圈）都还在。判据：逐条列出被改到的调用方。
  3. **改 Forge 树** → 4. **镜像 Neo 树**。判据：两树 `javac` `EXITCODE=0`。
  5. **护栏 → 打 jar → 部署 4 实例**。判据：全护栏 `FAIL 0`；4 个目标全部 `match=True`。
- 谁验收：开发者（本轮只部署、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

- 开发者指出根子在**模式没分开**：画过圈 ≠ 在 home 模式；拿画过的圈去钳跟随模式下的选点，是把两套语义混成一谈。
- 同一句里还问了"女巫为什么站在那边不动"——按前一句（「哪怕女巫旁边就是光」）与语音输入的同音关系，这里指的是**女仆**（她站着不动）与**矿**（"矿"→"光"）；本轮把"她为什么站着不动"的整条链在 `## 定位` 里一次讲清。

## 定位：她为什么站着不动（一条链讲完）

1. **选点全被否**：`MaidMineBehavior` 的扫描入口（`fullScanOres` / `pickFromCache` / 连锁队列）每块候选都要过 `WorkAreaClamp.allowsWork`。她在**跟随模式**（存档实证 `MaidIsHome=0`）、而**罗盘画过圈**（`maid_smart_player_circle=1`）、圈心 (-1547,112,-742)、半径 12（`misc.scheduleActivityRange`）、她本人 (-1473,79,-708)：**框内每一格都超圈** → 一律 false → 扫描永远给不出目标。
2. **只剩抬头兜底**：不查这道闸的只有 `firstOreAbove`（脚下 ±2 格、上方 1~10 格）。所以现场她只挖得到**头顶正上方**的煤（`mine autoCollect pos=(-1458,79,-702)`，脚位 y≈73）——**旁边**那块煤走的是被否掉的路，碰都不碰。
3. **没目标 = 站着**：`targetPos == null` → `chain_drive` 置 0 → 链路独占让位 → TLM **跟随**逻辑把她带回主人身边（现场 `mine stuck-reset pos=(-1465,73,-693)` 就是跟随走位后的落点）→ 站住不动。
4. **看门狗与播报**：15 秒零位移零进展 → `mine stuck-reset`（R917 口径保留目标）；空闲久了 `WorkStatusReporter` 报「附近没有值得挖的矿石」。**这就是她那句“站着不动”的全部来源**——不是任务没切、不是没镐（主手 `cataclysm:infernal_forge` 是 `PickaxeItem`）、不是矿表（煤矿石在表里）。

## 改法（两树镜像）

`WorkAreaClamp.allowsWork`：整段"玩家画的圈"分支删除，直接与 `allows` 同口径。

```java
public static boolean allowsWork(EntityMaid maid, BlockPos pos) {
    return allows(maid, pos);   // home → TLM 限制圈；非 home → 恒放行
}
```

- **为什么删得掉**：会开 home 的是「这块归你管」工具（`WorkAreaTool` 的 `action=set` 里就是 `sp.setHomeModeEnable` + `restrictTo`）与排班；**罗盘只写锚点、不开 home**。所以"画过圈却没 home"这条状态本来就是"守家没开"，此时她跟主人走、就该在主人身边干活。圈是"守家"的口径。
- **留下的两条**：home 模式那条仍是 `maid.isWithinRestriction(pos)`（TLM 自己的圈，半径由 `ScheduleRangeMixin` 抬到「排班活动半径」）；"没事找事走两步"的闲逛仍走 `allowsWander`（它在跟随模式下给一个绕主人的临时圈）——两条都不动。
- **删掉的方法没变孤儿**：`PlayerCircle` / `playerCircleCenter` / `playerCircleRadius` 仍被 `allowsWander` / `wanderAnchor` 使用。
- 两树差异：本次改动**无**（`allows` 两条树签名相同），两文件的新块逐字节一致。

## 触及文件

- `promaid_src/com/maidsmart/follow/WorkAreaClamp.java`
- `promaid_src_neo/com/maidsmart/follow/WorkAreaClamp.java`
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行 sha256 + 第六十一次同号重打）
- `rounds/R921-work-circle-clamp-when-outside.md`（加一句：本行取代其启发式）
- `rounds/R922-work-clamp-home-only.md`（本文件）

## 验证

- **两树编译**：`compile_promaid.bat` → `EXITCODE=0`；`compile_neo.bat` → `EXITCODE=0`。
- **全护栏**：`check_comment_style` **PASS**（0 处）；`check_preset` / `check_catch_swallow` / `check_i18n_keys` / `check_dev_voice` / `check_comment_dup` / `check_reflection` / `check_arch` / `check_panel_coverage` **FAIL 0 / WARN 0**；`check_workflow` **FAIL 0 / WARN 3**（历史遗留：PUBLISH_GUIDE 版本、1.2.4 归档哈希、13 个更早 jar 未登记）。
- **两个 jar**：`verify_jar_classes` **OK**（mixin 包登记 OK、注入签名 OK；`out` ↔ `jar` class 双向一致）。
- **未跑实机回归**（先停在这，等开发者指挥）。

### 部署（2026-10-13）

- 打 jar：`tools/build/build_promaid.py` → `patched/promaid-1.3.2-forge-1.20.1.jar`（**17582593** 字节，sha256 `70EBF870…D17D8E`）；`build_promaid_neo.py` → `patched/promaid-1.3.2-neoforge-1.21.1.jar`（**17618976** 字节，sha256 `FBFAC765…0BD6FB`）。
- 装机：`tools/deploy/deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201）；旧包已备份在 `patched/backup_old/`。
- 留痕：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行的 sha256 列更新为新字节，并追加「第六十一次同号重打」；R921 旧字节 `F97A0806…EB5DAE`（forge）/ `0ED7554B…6B286F`（neo）作废。
- 到此为止：**未跑回归、未验收、未 commit、未 push**（开发者指令："做完功能先部署，部署完等他指挥"）。

## 验收

- 状态：**未验收**（已部署@2026-10-13：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
  - 复现步骤 1（**本例正解**）：保持「罗盘画过圈 + 女仆非 home（跟随）」——把女仆带到**任意**位置（圈内圈外都行），站到煤/铁旁边 → 开挖矿任务 → 她应当**就地开挖**（日志 `mine autoCollect` / `mine chain burst block=…`），不再弹「附近没有值得挖的矿石」。
  - 复现步骤 2（**home 仍守圈**）：让工具/排班给她开 **home 模式** → 她的选点仍被限制在工作圈里；把圈挖空后她待在圈里等（不是追圈外的矿越挖越远）。
  - 复现步骤 3（**闲逛仍收得住**）：非 home 时"没事找事走两步"那种闲逛仍围绕主人（`misc.workStayInCircle` 那条没动）。
  - 判定：步骤 1 就地挖到旁边的煤 = 合格；若还是站着，请贴 `logs/latest.log` 里 `mine ` 开头的几行 + 该女仆 `MaidIsHome` / `MaidTask`。
- **预期内的口径变化（不是回归）**：非 home（跟随）时，挖矿/伐木/宰杀/农活的**选点不再被画过的圈限制**——她会跟着主人所在的位置选点（跟随模式的本来语义）。若开发者希望"跟随中也要在某个圈里干活"，正解是用「这块归你管」工具（它会同时开 home），而不是让罗盘画的圈在非 home 时生效。

## 边界（没动什么）

- **没动**：home 模式那条（`isWithinRestriction`）；闲逛那条（`allowsWander` 的临时圈）；`misc.compassPriority` / `misc.scheduleActivityRange` / `misc.workStayInCircle` 的默认值与语义；矿表 / 挖掘 / 接近 / 连锁 / 看门狗 / 掉落回收；R920 的 `mine.tagAuto`（保留——它补的是"模组矿石不在名单里"这个另一个真缺口）。
- **不新增开关、不改默认值**；版本号仍 **1.3.2**（`WORKFLOW.md` §5 "验收前不升号"）。
- **与 R921 的关系**：R921 加的是"她本人在圈外才放行"，本行把它整段换成"按模式分"。R921 的推理（含存档 NBT 实证）仍然有效，只是修法不够正；已在 R921 文件顶部加一句指向本行。
- 临时脚本（`tools/_r922*.py` / `_nbt3.py` / `_circle.txt`）**已删**；仓库根目录既有的 `_*` 文件与 `_tmp_pub` 等未动。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第六十一次同号重打。
- `rounds/R921-work-circle-clamp-when-outside.md`：顶部加"本行取代其启发式"的指引。
- `DEBT.md`：本轮不新增条目——本行是**收窄一处越界口径**（把不该在跟随模式生效的圈闸撤掉），不引入新的未验证行为。
