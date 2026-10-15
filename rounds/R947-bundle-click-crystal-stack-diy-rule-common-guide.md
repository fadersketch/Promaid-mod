# R947 压缩盒收纳袋式交互 / 末地水晶叠放 / DIY 新规 / 常用页介绍化 / 手册刷新

> 跑道：承接 `rounds/R946-compression-box-anchor.md`（压缩盒差异化补全）。本轮是开发者一次给的**五条**需求，
> 全部落地后按惯例**先部署**，工程面（回归 / 验收）等开发者指挥。

## 排班

- 五条需求一次做完，逐条独立自检、统一一次打包部署：

  1. 末地水晶多放**叠在同一格**（不再并排散开）；
  2. 压缩盒加**收纳袋式交互**（光标选中盒子 → 右击别人的一叠装进去）；
  3. 配置面板「常用」页重做成**介绍风格 + 相关链接**；
  4. **DIY 新规**：只关「介入女仆原有行为」的改动，新任务与新物品与默认模式逐项相同；
  5. 手册详细介绍**对齐现状**（并顺手清掉手册里残留的轮次标记）。

- 步骤：读现状 → 改 Forge → 镜像 Neo → 两树 `javac EXITCODE=0` → 11 护栏 `FAIL 0`
  → 打双 jar → 部署 4 实例 `match=True` → 台账/轮次记录 → 清临时脚本。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

开发者原话（五条，顺序同上）：

> 「1.放置末影水晶时我希望是五个叠在一起放，而不是分开5个。2.给压缩盒加入一个功能，类似于原版的收纳袋的使用方式，在物品栏先拿鼠标选中此物品，左键选中，随后我并不是会随着鼠标光标移动吗？将鼠标光标放在其他的物品上，然后右击。可以把这组物品装进去（前提是还有槽且这个物品可以被装。）3.常用功能不够常用，而且不够简单。我想要的是以介绍的风格附带相关链接。4.DIY模式新规：打开后只禁用本mod对女仆的各项改动，但新任务功能（挖矿/伐木/扫帚/宰杀/空袭）这些不管，这些功能各项配置与默认模式完全一样。新物品也是如此。要的效果就是：你不使用新任务功能和新物品，那就跟原版一样。你用还是可以照常用的。5.同时手册的详细介绍部分也变得陈旧了。」

## 定位与改法（五条）

### ① 末地水晶：并排 → 同一格叠放

- `BombPlacement.stepPayload` 的 CRYSTAL 分支：原先给第 2..N 枚水晶算的是**邻格偏移数组**（并排散开），
  现在改为**同一坐标叠放**（`base.x + 0.5 / base.y + 1.0 / base.z + 0.5`）——5 枚共处一格、一起起爆，
  观感就是「一摞水晶同时炸」，而不是「摊开成一排」。
- 取件计数仍在**取第一枚之前**算（`crystalBurst`），否则首枚出库会把盒子计数减 1、少放一个（R945 同款考量）。
- 重生锚那套（并排 5 个整块方块 + 逐锚充能）**不动**：锚是普通方块，并排才符合"锚阵"的直觉。

### ② 压缩盒：收纳袋式右键

- 语义（开发者原话）：物品栏里**左键拿起压缩盒**（挂光标上）→ 光标移到**另一叠物品**上**右击** → 那一叠整组进盒，
  装不下的留在原格；**反向也成立**（光标拿别的东西、右键点背包里的盒子）。
- 判据：`ClickAction.SECONDARY` + 盒子 `count == 1` + 目标那叠过 `CompressionBoxFilter.canStore`（带附魔 / 盒子本身不收）。
  一点都装不进去 → 返回 `false`，原版逻辑接管。
- **两棵树不一样（关键）**：
  - Neo 1.21.1 的原版 `AbstractContainerMenu.doClick` **本来就调**
    `ItemStack.overrideStackedOnOther` / `overrideOtherStackedOnMe` → `CompressionBoxItem` 里那两个 override 直接生效。
  - Forge 1.20.1 的原版**没有任何调用方**（扫遍编译用的 vanilla `-srg.jar` 与 forge-patched client jar：
    方法名只出现在 `ItemStack` / `Item` / `BundleItem` 三处**定义**里，一处调用都没有；原版收纳袋点击逻辑 1.20.5 才进菜单）
    → 只在 `CompressionBoxItem` 里 override = **死代码**。故 Forge 侧新增
    `com/maidsmart/mixin/CompressionBoxClickMixin.java`（`@Mixin(AbstractContainerMenu.class)`，`doClick` HEAD 注入、
    条件极窄，其余情况一律 `return` 交还原版），并在 `mixins.promaid.json` 登记。
- 盒子 NBT 写回、槽位刷新（`setChanged` / `set`）、光标余量（`SlotAccess.set`）逐项对齐原版收纳袋的做法。

### ③ 配置面板「常用」页：介绍风格 + 相关链接

- 新增 `RowDef permits ... , NoteRow`（`private record NoteRow(String text)`）：整段折行正文，专供"介绍"用
  （`InfoRow` 是两列、`SectionRow` 是单行，都撑不起段落）。
- `commonRows()` 重写：开头一段导览 → 「一键预设」块（`InfoRow("当前模式", …)` + 两个 `BtnRow`，
  仍然两步确认）→ 六个功能分组（战斗与自保 / 飞行与移动 / 工作 / 空中接敌 / AI 记忆对话语音 / 新物品），
  每组 **一段介绍 + 一个「前往 →」按钮**（`jumpTo("SECTION", null)`），点进去就是对应配置页——这就是开发者要的
  "介绍 + 相关链接"。

### ④ DIY 新规：只关"介入女仆原有行为"的改动

- `MaidConfigPreset` 新增两张表（DIY → **保留**，即不随 DIY 关闭）：
  - `DIY_KEEP_PREFIX`：`mine.` / `wood.` / `build.` / `broom.` / `bombing.` / `airRaid.` / `maneuver.` /
    `tether.` / `ride.` / `compressionBox.` / `accessory.`
  - `DIY_KEEP_EXACT`：`misc.*` 里的农场 / 收割 / 烹饪 / 酿造 / 宰杀那一批（前缀包不住）。
- `diyTargets()` 命中「新功能」即 `continue`（不再收进 DIY 关闭清单）；`VALUES` 由 5 条收到 3 条。
- 效果：**DIY = TLM 原版的女仆行为 + 我们新增的任务与物品照常可用**——
  不用新东西时体验与原版一致，想用就照常用（开发者要的正是这句）。
- `tools/verify/check_preset.py` 学会同一套分类（`is_new_feature()`），基线由
  `BASELINE_FLIP = 173/177` 改为 **`105/108`**，护栏不再与运行时口径漂移。

### ⑤ 手册详细介绍对齐现状

- 「功能总览」章按**当前面板结构**重排（⓪常用 / ①生产与工作 / ②AI 与对话 / ③战斗与自保 / … / ⑧物品，含子页清单）。
- 「DIY / 默认模式」段按新规重写；压缩盒章补**收纳袋式右键**与「真正的独特优势」两条；
  「物品 → 压缩盒」的配置位置改正；「智能挖矿 / 智能伐木」口径对齐（默认开、改名后行为）；
  「空中接敌机动」全量改名（原「接敌机动」）；空袭页写明 DIY 不再关空袭。
- 顺手把手册玩家可见文案里**残留的轮次标记**清掉（每树 9 处）——详见下面「验证」里的过程留痕。

## 触及文件

- 两树 × `com/maidsmart/combat/BombPlacement.java`（水晶叠放）
- 两树 × `com/maidsmart/box/CompressionBoxItem.java`（两个 override）
- `promaid_src/com/maidsmart/mixin/CompressionBoxClickMixin.java`（新增）+ `promaid_src/mixins.promaid.json`（登记）
- 两树 × `com/maidsmart/config/MaidConfigPreset.java`（`DIY_KEEP_PREFIX` / `DIY_KEEP_EXACT` / `isNewFeature`）
- 两树 × `com/maidsmart/config/PromaidConfigScreen.java`（`NoteRow` + `commonRows()`）
- 两树 × `com/maidsmart/build/BlueprintBookScreen.java`（DIY 确认弹窗与模式行文案）
- 两树 × `com/maidsmart/guide/*`（Basics / Work / Flight）
- `tools/verify/check_preset.py`（基线 105/108 + 分类模型）
- `rounds/RELEASES.tsv`（第八十六次同号重打）+ `rounds/R947-*.md`（本文件）
- **未动**：`MaidSmartConfig` 未新增配置项 / i18n 键；床位多放仍未做；爆炸威力与回收口径。

## 验证

- **两树编译**：`compile_promaid.bat` / `compile_neo.bat` → `EXITCODE=0`。
  - 中途一次失败：手册文案里两条 Java 字面量被我改坏（一条丢了 `"` `+`、一条把真换行写进了字符串）
    → 「未结束的字符串文字 / 非法字符」，修好后通过。
- **护栏（11 个）**：全 `FAIL 0`。
  - 中途 `check_comment_style` 报 **5 处 LONG-BLOCK**（新写的类头/成员注释超限：成员 > 8 行、类头 > 15 行）
    → 三处注释块缩短后 **PASS**。
  - `check_preset`：DIY 关闭 **105 / 108** 项，`FAIL 0 / WARN 0`（新基线）。
  - `check_workflow`：`FAIL 0` + 3 条历史 WARN（PUBLISH_GUIDE 版本、1.2.4 归档、更早 jar 未登记）。
- **文案体检**：写脚本复用护栏的注释掩码，逐条比对**字符串字面量**里的轮次/版本标记——
  手册三章每树 9 处清干净（`check_dev_voice` 对清理后结果 FAIL 0）。
  **遗留如实记录**：`assets/**/i18n/*.json` 与个别配置描述里还有历史 `v1.x.y` 标记（约 92 处，非本轮范围），
  需要时另开一轮统一清。
- **打包**：`gen_compile.py` 先重跑（新增的 `CompressionBoxClickMixin` 未进旧的 `compile_promaid.txt`，
  构建脚本因此拒绝打包旧字节码："无编译产物"）→ 再 `compile_promaid.bat` → `build_promaid.py` / `build_promaid_neo.py`。
  - forge **17602031** 字节（sha256 `10BA0C12…A09C15`）；neoforge **17636828** 字节（`B3C64A41…96112D`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 行更新 sha256 并列**第八十六次**同号重打；
  R946 的旧字节 `2934B262…2A122F`（forge）/ `EE65810E…1DE168`（neo）作废。
- **未跑实机回归**（已部署，等开发者实测）——需实测确认：5 枚水晶叠一格的爆炸观感与伤害、
  收纳袋式右键在**两棵树**里都真的响（Forge 靠 mixin）、常用页新排版在窄屏下的折行、DIY 切档后的实际项数。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- **判据**：
  - 盒内 ≥2 枚末地水晶 + 开「女仆背包延伸」→ 她一次放出 5 枚，**叠在同一格**一起起爆。
  - 物品栏左键拿起压缩盒 → 光标移到别的一叠上右击 → 整组进盒（装不下留在原格）；
    反向（光标拿东西、右键点盒子）同样进盒；带附魔的物品与盒子本身**不进**。
  - 「常用」页：能看到分段介绍文字，每组的「前往 →」都能跳到对应配置页并高亮。
  - DIY：新任务（挖矿/伐木/建造/扫帚/宰杀/空袭）与新物品在 DIY 下与默认模式**逐项相同**；
    只有"介入女仆原有行为"的那些被关掉。
  - 手册各章介绍与实际面板位置一致。

## 边界（没动什么 / 已知取舍）

- **床仍是单放**：床是原版**两格**方块，并排多放要一整套"每张床各占两格、朝向与占位互不重叠"的排布，
  不是套一行代码能覆盖的；要不要做、按几张，等开发者定。
- **`build.` 归入「新任务」——开发者已确认**：原话列的是挖矿/伐木/扫帚/宰杀/空袭，随后补充
  「建造是我忘说了。他也算新功能」，故 `build.` 留在 `DIY_KEEP_PREFIX` 里（DIY 下建造与默认模式逐项相同）。
  其余四项取舍（床位多放 / 飞行跟随 / 范围跳转 / 历史版本标记）开发者按「其他的就按你的想法来」维持本轮口径。
- **「飞行跟随」仍随 DIY 关**：它管的是女仆**原有**的跟随行为，按新规不属于"新任务"。
- **常用页没有再放"范围跳转"那一组**（上一版有）：按"少而精"和"介绍 + 链接"的口径删掉了，
  范围类参数在各自页面里更好找。
- **没新增配置项 / i18n 键**：DIY 新规只改分类，不改开关集合。
- **历史版本标记**：i18n / 配置描述里遗留的 `v1.x.y`（约 92 处）未清，另开一轮处理。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第八十六次同号重打。
- `DEBT.md`：不新增条目；`TD-04`（压缩盒 vs 精妙背包）继续按 R945 口径处置。
