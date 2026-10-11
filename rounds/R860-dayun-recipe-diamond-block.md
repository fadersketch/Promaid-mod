# 实测八百六十 · 大运合成：钻石 → 钻石块

## 需求 / 来源

开发者指令（本轮起因）：

> 大运的钻石→钻石块

即把饰品「大运」合成配方里的 4 个 **钻石**（`minecraft:diamond`）换成 4 个 **钻石块**
（`minecraft:diamond_block`）。配方形状不变（`IDI / RMR / IDI`：铁块×4 + **钻石块**×4 + 红石块×2 + 矿车×1）。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树逐字镜像、临时脚本用完即删。

## 评估

- 配方本体：`promaid_src/data/maid_smart/recipes/da_yun.json`（Forge，`result.item`）、
  `promaid_src_neo/data/maid_smart/recipe/da_yun.json`（Neo，`result.id`）——两树只有 result 键名不同，
  key 表里 `"D"` 都是 `minecraft:diamond`。
- 同一句"合成配方"被复刻在 6 处玩家可见文案里，改配方必须一起改，否则文档与实物不符：
  1. 物品说明 `item.maid_smart.da_yun.desc4`（两树 `lang/{zh_cn,en_us}.json`）；
  2. 配置面板"大运"分组提示行（两树 `PromaidConfigScreen.java` 的工具提示）；
  3. 手册里的合成内嵌 `[[#shaped;IDI;RMR;IDI;…D=minecraft:diamond;…>maid_smart:da_yun;@铁块×4 + 钻石×4 …]]`
     （两树 `GuideChaptersCombat.java`；`GuideScreen.java` 的 javadoc 同一串示例）；
  4. 手册 i18n（`assets/promaid/i18n/en_us.json`，以中文原文为 key）里 4 条含该配方的 key + 对应英文 value。
- **不动**压缩盒的配方（也用 `D=minecraft:diamond`，但那是 `PDP;DYD;PDP;…>maid_smart:compression_box`，
  与「大运」无关）——只改锚定 `>maid_smart:da_yun` 的那一串。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 定位配方 JSON + 全部复刻该配方的可见文案（含 i18n key/value） | 本文 §评估 | ☑ |
| 2 | 实现 | 两树配方 JSON：`D` 的 `minecraft:diamond` → `minecraft:diamond_block` | 2 文件 | ☑ |
| 3 | 实现 | 两树 6 处文案同步（物品说明 ×2、面板提示 ×2、手册内嵌 + javadoc ×2、i18n key/value ×2 树） | 8 文件 | ☑ |
| 4 | 护栏 | `check_workflow`/`check_preset`/`check_comment_style` 问题数不增；i18n key 与手册原文逐字节仍对得上 | 工具输出 | ☑ |
| 5 | 验证 | 两树 `javac EXITCODE=0`；打包自检（含 lang JSON 校验）；两树镜像一致 | 构建输出 | ☑ |
| 6 | 留痕 | `rounds/RELEASES.tsv` 同号重打留痕 + 更新本文 §验证 | 两处 | ☑ |
| 7 | 验收 | 交开发者实测（游戏里工作台摆 4 个钻石块能出大运；说明文字同步） | 待开闸 | ☐ |

## 改法

1. 配方 JSON：`"item": "minecraft:diamond"`（两树的 `da_yun` 配方）→ `"item": "minecraft:diamond_block"`。
2. 文案（中）：`铁块×4 + 钻石×4 + 红石块×2 + 矿车×1` → `铁块×4 + 钻石块×4 + …`；
   `铁块×4、钻石×4、…` → `铁块×4、钻石块×4、…`；内嵌 `D=minecraft:diamond;` → `D=minecraft:diamond_block;`
   （仅 `>maid_smart:da_yun` 那一串）。手册里"「铁钻铁 / 红车红 / 铁钻铁」"那句是退役更新日志的孤立 key（未在源码中引用），
   一并把 `钻石×4` 改到位、形状助记保持原样（钻 = 钻石块，读者据前后文可辨）。
3. 文案（英）：`4 diamonds` → `4 diamond blocks`（`+`/`,` 两种连接词各一）；`diamonds x4` → `diamond blocks x4`。
4. 不改版本号、不写 changelog。

## 触及文件

**改动**（两树各一份）：`data/maid_smart/{recipes,recipe}/da_yun.json`、
`com/maidsmart/guide/GuideChaptersCombat.java`、`com/maidsmart/guide/GuideScreen.java`（javadoc）、
`com/maidsmart/config/PromaidConfigScreen.java`（工具提示）、
`assets/maid_smart/lang/{zh_cn,en_us}.json`、`assets/promaid/i18n/en_us.json`。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0` |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge 17,568,848 B（`e0034305…b2b7df`）、Neo 17,607,782 B（`f4c879f8…556f48`） |
| 护栏 | `check_workflow` FAIL 0 / WARN 3；`check_preset` FAIL 0 / WARN 2；`check_comment_style` 2262（均与基线同） |
| 改动核对 | 配方 `D` = `minecraft:diamond_block`（两树）；新串 `钻石块×4` 计数 forge=8 / neo=8、da_yun 内嵌新串 4/4、旧串 `D=minecraft:diamond;…>maid_smart:da_yun` **0/0**（替换干净）；压缩盒内嵌 `D=minecraft:diamond;…>maid_smart:compression_box` 3/3 **未动**；两个 jar 内配方文件均含 `minecraft:diamond_block` |
| 镜像 | 逐串核对：两树 6 处文案与配方 JSON 的替换完全相同 |
| 部署 | **未部署**（开发者闸门） |
| 回归 | **未跑**（开发者闸门） |

## 版本与上传

- 版本保持 **1.3.2**；**不**部署、**不**跑回归、**不**发 release；同号重打按 WORKFLOW §5.2 在 `rounds/RELEASES.tsv` 留痕。

## 验收

**未验收**。

## 边界

- 只改「大运」配方的钻石→钻石块与所有复刻该配方的可见文案；压缩盒配方、其它含 diamond 的配方一律不动。
- 手册 i18n 里那条孤立的历史段落一并顺改（不新增文案）。

## 台账

- 无新增结构债；配方改动属内容调整，不动 `TECH_DEBT.md`。
