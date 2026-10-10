# 实测八百四十 · 干预项全部落成配置开关（默认开 · DIY 自动关）

## 需求 / 来源

开发者指令（本轮起因，紧接实测八百三十九的报告）：

> 这类功能全部都加入配置开关。并且默认开启。响应DIY以后自动关闭。

"这类功能"指上一轮报给他的两类**会改变女仆行为**的介入：

1. **两处根本没有开关**、只能在代码里硬问 `MaidConfigPreset.isDiy()` 的 mixin：
   `MaidSweepMixin`（每次平A强制横扫 + 跳劈不横扫）、
   `MaidCanAttackDefenseMixin`（正在打我方、但类型判不出的生物也算敌人）；
2. **`KEEP` 表里还剩几项会改变行为的**（我上轮点名：手持光源、卡住自愈、防塌/免搭柱、自动打标签）。

要求：全部变成真配置项、**默认开启**、**DIY 时自动关闭**。

仍有效的约定：不跑回归、不写 changelog / 功能介绍文案、版本号不擅自变动（本轮**不动 1.3.1**，
同号重打包按 WORKFLOW §5.2 留痕）、纯 Java/字符串逐字节镜像。

## 评估

- **DIY 的关闭机制是"表驱动 + 反射"，不需要逐个写清单**：`MaidConfigPreset.diyTargets()`
  遍历全部静态 `ConfigValue`，凡布尔**不在 `KEEP`/`SKIP` 里就置 false**（`FORCE_ON` 反语义项置 true）。
  所以"默认开启 + DIY 自动关闭"= ①新增的开关默认 `true` 且**不进 KEEP**；②把 KEEP 里那几项**移出去**。
  两件事做完，`planDiy()` 会自动带上它们，无须改 `applyDiy()`。
- **KEEP 里被点名的 7 项全部是默认 true 的布尔**（已核对 `.define(...)` 实参）：
  `mine.pillarGuard` / `mine.rideNoPillar` / `mine.stuckWatchdog` / `wood.tagAuto` /
  `wood.pillarGuard` / `wood.stuckWatchdog` / `misc.heldLightMaid` —— 所以"默认开启"已经成立，
  本轮只需要把它们移出 KEEP。
- **移出 KEEP 的代价（如实记，勿忘）**：这七项原先留在 KEEP 的理由写在表注释里（"关掉反而更危险"
  或"关掉只是把 bug 放回来"）。DIY 语义是"除本模组新添物品/模式外，一切回原版"，
  所以卡住自愈、防塌、免搭柱、自动标签、手持光源在 DIY 下**就是要关**——开发者已就此点头。
  观感差异：DIY 世界里的女仆卡住后不会自愈（要玩家自己救）、骑乘/坐下时会照原版搭方块、
  模组原木不再自动识别（只认名单）、她自己不再拿光源。
- **护栏要跟着改**，否则会误报：
  - `check_preset.py` 的 `PRESET_GATES`（"没有配置项、直接问 isDiy"的兜底名单）——本轮这两处
    **有了**配置项，应从该名单移除，并加入 `GATES`（"配置字段名 → 必须引用它的 mixin 文件"），
    检查强度不降反升：新规是"干预型 mixin 必须引用**自己那条**开关"。
  - `BASELINE_FLIP`（DIY 关掉的布尔条数基线）会因 +2 新项 +7 移出 KEEP 而 **+9**：
    forge 141 → **150**、neo 145 → **154**。
  - `ARCHITECTURE-SWITCHES.md`（配置清单）由 `tools/verify/gen_switch_inventory.py` 生成，
    `check_workflow` 会逐字比对——新增配置项后必须重跑。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 读两处 mixin、预设表结构、DIY 关闭机制、护栏三处（GATES/PRESET_GATES/BASELINE_FLIP） | 本文 §评估 | ☑ |
| 2 | 设计 | 定命名与语义：`combat.sweepAlways`（平A横扫整条特性，含跳劈不横扫那一半）、`combat.attackThoseAttackingUs`（类型闸对"正在打我方"让位）；均默认 true、不进 KEEP | 本文 §改法 | ☑ |
| 3 | 实现 | 两树 `MaidSmartConfig`：+2 声明 +2 定义（combat 组末尾，带 comment/translation） | 两树 | ☑ |
| 4 | 实现 | 两树两处 mixin：`isDiy()` 硬闸 → `MaidSmartConfig.<新项>.get()` 真开关（关掉 = 回 TLM 原版） | 两树 | ☑ |
| 5 | 实现 | 两树 `MaidConfigPreset.KEEP`：移出 7 项（手持光源 / 卡住自愈 ×2 / 防塌 ×2 / 免搭柱 / 自动标签） | 两树 | ☑ |
| 6 | 实现 | 两树 `assets/maid_smart/lang/{en_us,zh_cn}.json`：+2 配置翻译 key（面板可见名） | 4 个文件 | ☑ |
| 7 | 护栏 | `check_preset.py`：`GATES` +2、`PRESET_GATES` 清空、`BASELINE_FLIP` → 150/154；重跑 `gen_switch_inventory.py` 刷新 `ARCHITECTURE-SWITCHES.md` | 2 个文件 | ☑ |
| 8 | 镜像 | Neo 树逐文件对齐（SRG↔Mojang；纯 Java/字符串逐字节一致） | 引用计数比对 | ☑ |
| 9 | 验证 | 两树 `javac EXITCODE=0`；四护栏问题数不增；`check_preset` FAIL 0（含新基线）；打包自检 | 构建输出 | ☑ |
| 10 | 交付 | 打包 → 桌面快照 → 装本机 4 实例（**不跑回归**） | deploy 输出 | ☑ |
| 11 | 留痕 | RELEASES.tsv 同号重打包留痕 + TD-11 更新 + 本文 §验证/§验收 | 三处 | ☑ |
| 12 | 验收 | 交开发者实测（含"面板里能看到这两条开关""切 DIY 后它们变关"） | 已验收 2026-10-10 | ☑ |

## 改法

1. **新配置项**（combat 组，默认 `true`）：
   - `combat.sweepAlways`「平A必横扫（默认开）」——无横扫之刃附魔也按 50% 攻击力倍率横扫，
     跳劈（真·空中）保持单体暴击；关闭 = TLM 原版（只有带附魔才横扫，空中照扫）。
   - `combat.attackThoseAttackingUs`「反击类型判不出的攻击者（默认开）」——它此刻锁着她/主人或
     刚打过我们这一边时，类型闸放行（给 ALEX 生物这类"类型判不出威胁"的模组生物用）；
     关闭 = TLM 原版类型闸。
2. **两处 mixin**：把 `MaidConfigPreset.isDiy()` 那一句换成对应 `MaidSmartConfig` 开关读取；
   注释写明"DIY 由预设把这条开关置 false，所以 DIY 下行为回原版"。
3. **KEEP 表**：删除 `mine.stuckWatchdog`、`mine.pillarGuard`、`mine.rideNoPillar`、
   `wood.tagAuto`、`wood.stuckWatchdog`、`wood.pillarGuard`、`misc.heldLightMaid`；
   表注释同步改写（说明这几类现在随 DIY 关，代价是什么）。
4. **护栏**：`GATES` 增两条映射；`PRESET_GATES` 清空（保留机制与注释，供将来无配置项的介入用）；
   `BASELINE_FLIP` = 150 / 154。
5. 版本号**不动**（1.3.1）。不写文案、不写 changelog。

### 不做

- 不动 `KEEP` 里其余项（AI/感知/情绪/播报/语音/手册/显示与标记/安全护栏）——它们是**纯附加**或
  **只在自家功能里生效的护栏**；DIY 关了自家功能后它们的开关已无实际作用。
- 不新增第三个开关把"跳劈不横扫"独立出来：它和"平A必横扫"是**同一条特性**的两半
  （关掉整条 = 回原版），独立拆开只会让面板更像仪表盘。

## 触及文件

**改动**（两树各一份）：`com/maidsmart/config/MaidSmartConfig.java`（+2 项）、
`com/maidsmart/config/MaidConfigPreset.java`（KEEP 移出 7 项）、
`com/maidsmart/mixin/MaidSweepMixin.java`、`com/maidsmart/mixin/MaidCanAttackDefenseMixin.java`、
`assets/maid_smart/lang/{en_us,zh_cn}.json`（+2 配置翻译 key）。

**工具**：`tools/verify/check_preset.py`、`tools/verify/ARCHITECTURE-SWITCHES.md`（生成物）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0`（Forge 463 源 / Neo 466 源；警告数与上轮同档） |
| `check_preset` | **FAIL 0 / WARN 2**（仅 `broom.enable` 重复定义，TD-08 旧账）；DIY 关闭条数 **Forge 150 / Neo 154**，与新基线一致；两条新开关各被自己的 mixin 引用（`GATES` 通过）；`PRESET_GATES` 已空 |
| `check_comment_style` | 929 文件 / **2258 处**（与基线同数）。**过程中的一次偏差已收口**：KEEP 表 javadoc 一度写到 13 行（>8 记 MEMBER LONG-BLOCK，两树各 +1 → 2260），已压回 6 行并改为陈述现状（不写历史），回到 2258 |
| `check_changelog` | 与上轮同档（FAIL 明细未增；未写新条目） |
| `check_workflow` | **FAIL 0 / WARN 3**（三条旧 WARN：PUBLISH_GUIDE 版本、1.2.4 未归档、13 个旧 jar 未登记）；ARCHITECTURE-SWITCHES 与重生成结果一致 |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge 18,928,098 B（`989cfc03…cd02d1`）、Neo 18,966,012 B（`2632120f…981fd1`） |
| 镜像 | 逐文件核对：`MaidSmartConfig` / 两处 mixin / `MaidConfigPreset` 的两树差异**仅** `ForgeConfigSpec`↔`ModConfigSpec` 等 MC API 名；KEEP 与注释逐字节一致 |
| 部署 | `deploy_all.py`：4 实例 **match=True**（1.21.1-NeoForge、1.20.1-Forge、龙之冒险、pack1201） |
| 回归 | **未跑**（开发者闸门） |

## 验收

**已验收@2026-10-10** —— 开发者开闸「开始跑部署和回归，然后写 changelog 和 release 声明」；本轮专服回归
两加载器各两件全过、本机 4 实例 `match=True`；重点已核：面板里出现这两条新开关、切 DIY 后它们自动变关。

## 边界

- DIY 的"回原版"是**逐步逼近**：本轮把最后两处"无开关"的介入补上开关、并让 7 项行为类 KEEP 随 DIY 关；
  `KEEP` 里剩下的都是纯附加/自家功能护栏，不再改变"她做什么"。
- 关掉这七项的观感代价已写进 §评估（卡住不自愈等），并会在配置项 comment 里写明"关闭 = 回原版"。

## 台账

- **TD-11**（DIY 全量语义）—— 本轮**结清主项**：两处无开关介入 → 有开关；7 项行为类 KEEP → 随 DIY 关。
  剩余 KEEP 项已逐类核对为纯附加/自家护栏。
