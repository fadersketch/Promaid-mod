# R973 挖矿/伐木：配置表拉黑优先（严重 bug）+ 无目标提醒 + 空闲闲逛 + 矿表界面

## 排班
- **跑道**：A 修错轮（开发者报障，含一条严重功能失效）。三件事同一片区（矿/木两张配置表 + 两个采集行为 + 它们的配置界面），一次改完一次部署。
- **开工前置**：承接 `rounds/R972-*.md`；读 `task/MaidMineBehavior`、`task/MaidWoodBehavior`、`task/MaidWorkBehavior`（基类）、`schedule/ScheduleTaskAvailability`、`config/MaidSmartConfig`（两张表的 `defineList` 校验）、`config/PromaidConfigScreen`（矿表子页：网格 + `MinableList` + `removeOre`）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位"配置表失效"根因 | 菜单去掉煤炭 → 表里没有它了，但 `oreValueOf` 的 **tag 兜底**（`mine.tagAuto` + `forge:ores`/`c:ores` 标签 → 值 300）又把它算成矿；`ScheduleTaskAvailability.hasOreInRange` 里还**另写了一份同样口径**（两处各漏一半） | ☑ |
| 1 | 拉黑语义 | 两张表新增 `!id` 行 = **显式拉黑**；行为侧 `ORE_EXCLUDED`/`WOOD_EXCLUDED` **一票否决**（优先级：拉黑 → 表项 → tag 自动） | ☑ |
| 2 | 判据唯一化 | `MaidMineBehavior.isTargetOre(BlockState)` / `MaidWoodBehavior.isTargetWood(...)` 成为公开唯一判据；排班探测改为调用它们（删掉自己那份重复逻辑） | ☑ |
| 3 | 界面写拉黑 | 网格右上角的小叉 / 列表「删除」= 写 `!id`（不再是简单删行）；拉黑行在列表里显示「已拉黑·不当目标」，其按钮变「恢复」（= 解除拉黑）；重新加入会自动撤销拉黑 | ☑ |
| 4 | 无目标提醒 + 空闲闲逛 | 挖空/砍空一片（全量扫描确认）→ 气泡「这片区域没有目标矿物/可砍的树木了」**30 秒/条**；同时 `idleWander()` 在工作圈内闲逛（与烹饪/酿造/宰杀同一口径）——不再原地站桩 | ☑ |
| 5 | 矿表界面瘦身 | 表格里**删掉废弃的"重要度/价值"输入框**（选矿早已改成"圈内最近优先"）；底部说明由超屏一整行改成**两行短句**并明说"点图标右上角的小叉取消/拉黑"；网格给被拉黑的方块画红框红叉 | ☑ |
| 6 | 两树镜像 + 编译 | 两树同改（按树替换 SRG/官方名）；`EXITCODE=0` ×2 | ☑ |
| 7 | 护栏 / 打包 / 部署 / 回归 | 14 支 `FAIL 0`（首轮 `check_comment_style` 报 4 处：两条新注释用了"玩家反馈"字样，已改写）；双 jar；4/4 `match=True`；四件回归全 `verdict: PASS` | ☑ |
| 8 | 台账 / 文案 / 收尾 | `RELEASES.tsv` 第一百零九次；`DEV_GUIDE.md` → 九百七十三；发布文案补一条；`机制详解.md` 同口径 | ☑ |
| 9 | 交开发者验收 | 开发者实测后给出结论 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = ① **严重 bug**：配置面板 → 挖矿 → 目标矿物，把煤炭点掉（图标右上角的小叉）→ 女仆**不再挖煤炭**（图表里煤炭变红框红叉；踩到煤矿也不会去挖）；点该行的「恢复」她立刻又开始挖；伐木同理（把橡木叉掉 → 不砍橡木）。② 把检索范围内的矿全挖光（或站到一片空地开矿）：约半分钟内她冒一句「这片区域没有目标矿物了，我先在这边活动～」，并在工作圈里**来回走动**（不再站着不动）；伐木同样。③ 打开矿表页：界面上**没有**"重要度/价值"输入框，底部两行短说明完整可见（不再超出屏幕两侧），说明里明确写了"点图标右上角的小叉取消/拉黑"。

## 需求 / 来源
- 开发者（第 3 条，严重）：「我发现我们的挖矿和伐木配置表似乎现在一点用都没有了。哪怕在挖矿功能里面把煤炭给叉掉了，让女仆不以此作为目标，但女仆还是照挖。似乎是因为挖矿功能的更改没有衔接上配置表。」
- 开发者（第 4 条）：「当女仆持续一段时间内没有检测到目标矿物/树木时，应该要通过对话气泡的方式提醒一下主人这个区域内没有了，然后自身的移动逻辑恢复为空闲时的行为逻辑（而不是像现在这样没目标了就完全傻站着不动了）。挖矿已经有这个功能了，但是似乎没办法再触发了。伐木则是压根儿就没有。」
- 开发者（第 5 条）：「挖矿功能的字段如图所示，超出了屏幕。同时没有告诉玩家需要点击方块的右上角的叉在列表中取消这个方块。而且现在已经废弃的重要度仍然还残留在这个表格里。」

## 改法
1. **拉黑一票否决（行为层）**：`MaidMineBehavior` 新增 `ORE_EXCLUDED` 集合与 `isTargetOre()`；`oreValueOf()` 改成 **拉黑 → 矿表 → tag 自动** 三级；`loadCustomOres()` 解析 `!id` 行进拉黑集合并随配置重建一起清空。`MaidWoodBehavior` 完全对称（`WOOD_EXCLUDED` / `isTargetWood` / `woodValueOf`），`isWoodState` 收敛为 `woodValueOf(state) != null`（此前两者各写一份"名单 + tag"）。
2. **判据唯一化（排班层）**：`ScheduleTaskAvailability.hasOreInRange/hasWoodInRange` 里那段"名单 + tag 自动"的重复判定删掉，改为调用两个行为的公开判据——**同一件事只留一处实现**，这类"两处判定各漏一半"的 bug 不会再出现。
3. **配置格式**：`mine.oreValues` / `wood.values` 的 `defineList` 校验放宽为"`!id`（拉黑，长度 > 1）或 `id=value`"，两条 `.comment` 补一句"行首加 `!` = 拉黑"。没有新增配置键（面板覆盖/词条表都不受影响）。
4. **界面**：`removeOre` 改为写 `!id`；新增 `setOreBlacklist(id, on)` / `isBlacklisted(id)`；`setOreValue` 在加入时撤掉同名拉黑行；列表的「删除」对普通行=拉黑、对拉黑行=恢复；`MinableEntry` **去掉价值输入框**（字段 + 构造 + 渲染 + 点击转发四处一并清理，避免留死代码），改为 `blacklisted` 标记；网格对拉黑方块画红框 + `✖`；底部说明拆两行。
5. **空闲行为**：基类 `MaidWorkBehavior` 新增 `idleWander(maid)`（每 40 拍在工作圈内取随机点，`WorkAreaClamp` 钳制，走"散步速度倍率"）与 `notifyNoTarget(maid, gameTime, text)`（复用 `NO_BLOCK_REPORT_SINCE` 限频表，30 秒/条）；挖矿/伐木在"全量扫描确认本框为空"的分支里各调一次。

## 触及文件
- 两树 × `com/maidsmart/task/MaidMineBehavior.java`、`MaidWoodBehavior.java`、`MaidWorkBehavior.java`。
- 两树 × `com/maidsmart/schedule/ScheduleTaskAvailability.java`、`com/maidsmart/config/MaidSmartConfig.java`、`com/maidsmart/config/PromaidConfigScreen.java`。
- 台账 / 文档：`rounds/R973-*.md`（本文件）、`rounds/RELEASES.tsv`（第一百零九次同号重打）、`DEV_GUIDE.md`（区间 → 九百七十三）、`机制详解.md`（两条补记）、4 份发布文案（加一条）。
- **未动**：矿/木两张表的**参数**（半径/预算/速度/距离…）、`MINE_BREAKABLES`/`MINE_DISABLED_BREAKABLES`（障碍物两张表）、锚点迁移与搭方块逻辑、`mine.tagAuto`/`wood.tagAuto` 两个开关本身、`MINE_CREATIVE_DEFAULT_VALUE`（新加入方块仍按它入库）。

## 验证
- **两树编译**：`javac EXITCODE=0`（两树）。
- **护栏 14 个**：全 `FAIL 0`。
- **打包**：forge **17652023** 字节（sha256 `4d1abcc9…009d5e`）；neoforge **17699329** 字节（`b8510224…bde0ec`）。
- **装机**：4 个本机实例全部 `match=True`（`elevated rc: 0`）。
- **回归**：四件全 `verdict: PASS`。
- **留痕**：`RELEASES.tsv` 两条 1.3.2 行 **第一百零九次**同号重打；R972 旧字节 `bbc82602…e55d95`（forge）/ `f120f51a…4f2458`（neo）作废。

## 验收
- 状态：**未验收**（已部署；未 commit、未 push）。
- **判据**：见"谁验收 / 怎么验"三条；核心是"叉掉的真不挖了""挖空一片会说话并且会走动"。

## 边界
- **拉黑只影响"当不当目标"**：不影响障碍物名单（挖穿开路）、不影响掉落物拾取。
- **拉黑行是配置的一部分**：它会写进 `config/promaid-common.toml` 的 `oreValues`/`woodValues` 里（形如 `"!minecraft:coal_ore"`），服主可以直接手改；删掉这行 = 解除拉黑。
- **tag 自动识别仍在**：只对"没被拉黑、也不在表里的带标签方块"生效（值 300）——模组矿石依旧不用手加点名。
- **气泡 30 秒/条**：与"搭方块材料耗尽"播报共用同一张限频表（一只女仆同一时刻只干一件活，不冲突）。
- **闲逛仍在工作圈内**：跟随模式不逛离主人、home 模式不逛出圈（`WorkAreaClamp.allowsWander` 判定）；她只是"在圈里活动"，不会跑远。
- **不含"跨圈找矿"**：本框确认空时仍由既有的 `slideAnchor` 做逻辑迁移，本轮只补"提醒 + 别站桩"。

## 台账
- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第一百零九次同号重打。
- `DEBT.md`：不新增条目。
- `DEV_GUIDE.md`：发布区间 `八百五十九 ~ 九百七十三`。
