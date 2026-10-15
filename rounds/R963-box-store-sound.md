# R963 压缩盒「右键收纳」加一记音效（与玩家穿装备同款）

## 排班
- **跑道**：B 新功能（开发者指令）——一句话判定依据：给既有交互加一记音频反馈（右键收纳成功时响一下），不改任何判定与数据；属"新增能力/表现"而非修错。
- **开工前置**：读过 `WORKFLOW.md` §0~§4、`ARCHITECTURE-SWITCHES.md` 压缩盒节、`DEBT.md`、`rounds/RELEASES.tsv` 1.3.2 两行（第九十八次 R961）、上一轮 `rounds/R962-*.md`，以及压缩盒现有三条交互路径的源码（{@code CompressionBoxItem} 的两个收纳钩子 + Forge 的 {@code CompressionBoxClickMixin}）。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定"右键收纳"这条路的实现面 | 两个方向：盒子在光标→右键槽位（Forge 由 `CompressionBoxClickMixin` 接管、Neo 原版菜单调 `overrideStackedOnOther`）；盒子在槽里→右键把光标那组收进去（仅 Neo 调 `overrideOtherStackedOnMe`） | ☑ |
| 1 | 取"穿装备声"的准确常量 | 原版 `ARMOR_EQUIP_GENERIC`；1.20.1 SRG 名靠 javap 反查 `SoundEvents` 静态初始化确认为 `f_11675_`；1.21.1 是 `Holder<SoundEvent>`，需 `.value()` | ☑ |
| 2 | 改 Forge 树 | 物品类加 `playStoreSound` + 两处钩子调用；mixin 成功分支调用 | ☑ |
| 3 | 镜像 Neo 树 | 同一函数逐字镜像（Mojang 名映射） | ☑ |
| 4 | 两树编译 | `javac EXITCODE=0` ×2 | ☑ |
| 5 | 护栏 | 14 支全 `FAIL 0` | ☑ |
| 6 | 打包装机 | 4 个目标与 `patched/` 同字节（`match=True`） | ☑ |
| 7 | 回归 | 四件全 PASS | ☑ |
| 8 | 交开发者验收 | 开发者实测后给出结论 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = 打开任意容器（背包/箱子），光标上拿 1 个压缩盒 → 右键点一组物品，听一下是否响"穿装备"那一声；把盒子放进槽里、光标拿别的东西右键点盒子（Neo 侧）同样应响；故意用装不进去的格子（满盒/带附魔的物品）确认**不响**。

## 需求 / 来源
- 开发者：「使用压缩盒右键收纳东西的时候能不能加一个音效？就跟玩家穿上装备的声音使用一个音效。」

## 评估
- **现状**：右键收纳这条交互有两个方向，且**两棵树的接法不同**——Forge 1.20.1 原版菜单没有收纳袋式钩子的调用方（原版 1.20.5 才进菜单），所以 Forge 由 {@code CompressionBoxClickMixin} 在 `doClick` 头接管并复刻同款逻辑；Neo 1.21.1 原版菜单本来就会调 `overrideStackedOnOther` / `overrideOtherStackedOnMe`，物品类里那两份 override 直接生效（Forge 侧是死代码，留作对照）。
- **音效口径**：原版"穿装备"的通用声是 `ARMOR_EQUIP_GENERIC`（`ArmorItem` 之外的装备走它兜底）；原版播放口径 = 音量 1.0 / 音调 1.0 / 声源 `PLAYERS` / 落点取实体自己脚下 → 本处逐条照搬（用 `Entity.playSound(SoundEvent, float, float)`，与 TLM/本模组既有播放方式一致）。
- **常量核实**：1.20.1 的 SRG 名不靠记忆——javap `SoundEvents` 的静态初始化段，找 `ldc "item.armor.equip_generic"` 后面紧跟的 `putstatic`，确认为 `f_11675_`（前一行是 `f_11674_`＝elytra、后一行 `f_11676_`＝gold，序列对得上）。1.21.1 侧编译期先报「Holder<SoundEvent> 无法转换为 SoundEvent」，按本树既有惯例（`TRIDENT_THROW.value()` / `WIND_CHARGE_BURST.value()`）补 `.value()`。
- **不新增开关**：这是"表现反馈"档（与建造期每块的放置音效、挥臂同档），不是行为介入；嫌吵的玩家有原版「玩家」音量滑条可调，且响度/音调与原版装备声一致，不单独给开关（也避免为一个音效动面板/i18n/预设三处生成物）。
- **级别**：**P3**（纯音频反馈，无功能与数据影响）。
- **风险**：极低——只在"收纳真的成功"的那一条分支上追加一行播放调用；异常一律吞进 `Diag.swallow`（容器点击是高频路径，绝不把异常抛进菜单链，与既有写法一致）。

## 改法
1. **新增 `CompressionBoxItem.playStoreSound(Player)`**（两树同源）：`player.playSound(ARMOR_EQUIP_GENERIC, 1.0F, 1.0F)`；Neo 侧常量取 `.value()`。注释写明"同玩家穿装备的声"。
2. **Forge 物品类两处调用**：`m_142207_`（盒子在光标→槽）与 `m_142305_`（盒子在槽→光标）的成功分支各加一行（Forge 侧为死代码，随镜像一并写上，防将来启用钩子时漏音）。
3. **Forge `CompressionBoxClickMixin`**：成功分支 `menu.m_142503_(carried)` 之后、`ci.cancel()` 之前调用一次（Forge 的**活路径**）。
4. **Neo 物品类两处调用**：`overrideStackedOnOther` / `overrideOtherStackedOnMe` 的成功分支各加一行（Neo 的**活路径**）。

## 触及文件
- 两树各 1 个物品类：`box/CompressionBoxItem.java`（新增 1 个静态方法 + 2 处调用）。
- 仅 Forge 1 个 mixin：`mixin/CompressionBoxClickMixin.java`（成功分支 +1 行）。
- 新配置项 / 新 i18n key / 新 mixin：**0 / 0 / 0**（mixin 是既有文件的既有方法）。
- 生成物：`panel_coverage.txt` 与 `ARCHITECTURE-SWITCHES.md` 本轮不涉及（未动面板/配置），13 支生成物类护栏重跑只为确认无漂移。
- 不动：`CompressionBoxData`（收纳数据与上限）、`CompressionBoxFilter`（能不能进盒的判据）、`CompressionBoxMaidInv`（女仆侧视图）、`CompressionBoxScreen` / 盒子 GUI（本轮的"右键收纳"不含 GUI 搬箱）。

## 验证
- **编译**：两树 `javac EXITCODE=0`。
- **护栏**：14 支全 `FAIL 0`（新注释块 2 行、`playStoreSound` 方法体带 `Diag.swallow`，`check_comment_style` / `check_catch_swallow` 均无告警）。
- **镜像**：两树的 `playStoreSound` 逐字对应（仅 SRG ↔ Mojang 名与 `.value()` 不同）；`check_tree_mirror` 无新增差异项。
- **打包**：forge `17634501` / neo `17681551` 字节；部署 4/4 `match=True`；新 sha256 forge `a875e301…35839f` / neo `350d312b…104754`（台账两行已改 sha 列并追加「第九十九次同号重打」说明）。
- **回归**：四件全 PASS（`test_server` / `test_maid_load` × 1201 / neoforge1211）。
- **反证**（改前 / 改后）：收纳判据逐字未改——`action != SECONDARY` / 盒子必须 1 个 / `CompressionBoxFilter.canStore` / `merge` 后"一点没装进去就不接管"四条原样；失败分支一行不响（只有成功分支追加调用）；`CompressionBoxData` / `CompressionBoxFilter` 两文件零改动；物品类的既有 `use`（右键打开 GUI）与本轮无关、未动。
- **未做**：**没有实机听声**（本环境没有游戏）——响度/音调/是否与 GUI 打开声叠在一起留给开发者实测；改动能被证到的最深处是"两树编译 + 14 护栏 + 四件回归 + 收纳判据零改动"。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）
- 状态：**未验收**。已部署@2026-10-14、已回归@2026-10-14（四件全 PASS）。
- 未验收之前：不写 changelog、不发平台（只**追加**到同一份未发布的 1.3.2 文案）。

## 边界（没动什么）
- **不动收纳逻辑**：能不能收、收多少、剩多少余量、哪些不许进盒——四条判据与数据格式逐字未改。
- **不动 GUI 路径**：盒子界面（`CompressionBoxScreen`）里的搬箱/拖拽不响（开发者点名的是"右键收纳"）。
- **不动女仆侧**：她顺手把掉落物塞进盒子（`insertItemStacked`）不响——那不是玩家的右键动作，且会变成"捡东西就叮一声"的噪音。
- **不动音量体系**：不新增音量配置、不走独立声源，沿用原版 `PLAYERS` 滑条。
- **不动版本号**：仍 1.3.2。

## 台账
- 清掉的债：无（本轮是开发者点名的表现增强）。
- 新增的债：无。
- 配额：**功能轮带一条 WARN**——`check_workflow` §8 口径"最新轮次是功能轮时不得有未清的 P0/P1 债"，而 **BD-04(P1) 仍 `未清`**。为什么不在这轮清它：本轮是**开发者当场点的小功能**，按他「后续的工程不要做，等我指挥」的长期口径，不擅自把别的债塞进来。`FAIL 0` 照旧满足（该项只有 WARN）；同 R906 / R907 / R960 / R961 的处理，如实留痕（上一轮只落 dev 面的 R962 让这条 WARN 在这两轮之间暂时没显示，本轮的记录把它接续回来）。
