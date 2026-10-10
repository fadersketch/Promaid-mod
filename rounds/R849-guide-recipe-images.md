# 实测八百四十九 手册：把缺的合成表图片补齐（骑乘指挥棒、巡逻航图、药剂手册、排班表）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）
- **跑道**：B（手册内容补齐；纯文案 + 合成表 token）。
- **开工前读过**：`GuideScreen` 的 `[[#shaped;…]]` / `[[#shapeless;…]]` 语法（实测八百一十三：`parseRecipe` 支持 shaped / shapeless、`>` 产物、`@说明`；`drawRecipe` 画产物名 + 说明行，说明走 `PromaidI18n.tr`，英文客户端的 token 来自英文 value 所以 @note 显示英文）· `R846`（手册改写方法论：三种转义形态）· `R848`（同一批手册工作里"先打印原始行、再照抄替换"的做法）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 侦察：把手册里**写了合成但没配 `[[#…]]` 图**的条目扫全（对照 `data/maid_smart/recipes/*.json`） | 清单 + 每条的真实配方落表（4 件：骑乘指挥棒 / 巡逻航图 / 药剂手册 / 排班表） | ☑ |
| 1 | 补图：按 `parseRecipe` 语法逐条补，说明文字用真材料；正文里重复的材料句去掉 | 4 条 token 全部落地；旧片段两树全消 | ☑ |
| 2 | i18n：正段落与 key 逐字节一致（含 4 处改写 + 4 键新增，键/值同时；tag 材料用物品 id 画图） | 断言脚本全过；`en_us.json` 6899 → **6903** 键；JSON 解析通过 | ☑ |
| 3 | 编译 + 打包（同号第 14 次）+ 开发者验收 | `EXITCODE=0`；开发者在手册里看到图 | ☐（打包已完成、验收待开发者） |

- **谁验收 / 怎么验**：开发者翻手册对应章，肉眼比对图与游戏内 JEI 配方一致。

## 需求 / 来源
- 开发者本轮开篇（无编号那一条）：骑乘指挥棒和巡逻航图这类道具"没有配上对应的合成表图片"。
- 第 0 步扫全后的结论：**正好 4 件**（不是两件）——另外两件是同一毛病的：酿造章里的「女仆药剂手册（水瓶 + 书本合成）」与排班表章里的「纸 + 墨囊或荧光墨囊 无序合成」。

## 评估
- **跑道**：B。落点：`GuideChaptersCombat` 骑乘指挥棒章（正文只有"合成：拴绳 + 木棍"）· `GuideChaptersFlight` 巡逻航图章（"无序合成：纸 + 指南针 + 墨囊"在正文里）· `GuideChaptersWork` 的酿造段与排班表章（材料写在正文括号里）。
- 真实配方（`data/maid_smart/recipes/*.json`，本轮**未动**）：ride_baton = 拴绳 + 木棍（无序）· patrol_chart = 纸 + 指南针 + `maid_smart:ink_sacs` 标签（= 墨囊 / 荧光墨囊，无序）· brew_manual = 水瓶 + 书本（无序）· schedule_book = 纸 + `ink_sacs` 标签（无序，产物带常驻附魔光效）。
- 前置四问：①重复性：与实测八百一十三 同机制、不同条目（那次只给 5 件画了图）；②语义：补既有章的内容 + 去掉正文里重复的材料句；③耦合：合成表 token 只在手册内解析，解析失败只是跳过不崩界面；④性能：无。

## 改法
- 每条都按**既有的 813 版式**（正文说用途 → 紧跟一段 `§e合成§r（工作台，无序）：` + `[[#…]]`），并把正文里重复的材料句去掉（材料改由图里的 `@说明` 承担）：
  1. 骑乘指挥棒：`[[#shapeless;minecraft:lead;minecraft:stick;>maid_smart:ride_baton;@拴绳×1 + 木棍×1]]`（正文"（合成：拴绳 + 木棍）"删除）
  2. 巡逻航图：`[[#shapeless;minecraft:paper;minecraft:compass;minecraft:ink_sac;>maid_smart:patrol_chart;@纸×1 + 指南针×1 + 墨囊×1（荧光墨囊也行）]]`（正文"（无序合成：纸 + 指南针 + 墨囊）"删除）
  3. 药剂手册：`[[#shapeless;minecraft:potion;minecraft:book;>maid_smart:brew_manual;@水瓶×1 + 书本×1]]`（正文"（水瓶 + 书本合成）"删除；这段紧跟酿造段、放在〔烤牛排举例〕之前）
  4. 排班表：`[[#shapeless;minecraft:paper;minecraft:ink_sac;>maid_smart:schedule_book;@纸×1 + 墨囊×1（荧光墨囊也行）]]`（正文"（纸 + 墨囊或荧光墨囊 无序合成，随便放哪格都行——"删除；"无序"由图里的版式承担）
- **tag 材料的处理**：`maid_smart:ink_sacs` 是标签（墨囊 + 荧光墨囊），`GuideIcons.stack(id)` 只能解析物品 id → 图里用 `minecraft:ink_sac` 画，说明里写"（荧光墨囊也行）"，与真实配方等价。
- 被否掉的方案：把 tag 写进 token 让 `GuideIcons` 去解析标签（要改 `GuideScreen`/`GuideIcons` 的解析层，超出"补图"范围，且旧版对所有 tag 都画不出）。

## 触及文件
- `promaid_src{, _neo}/com/maidsmart/guide/GuideChaptersCombat.java`（骑乘指挥棒：正文 1 处 + 新增 1 段）
- `promaid_src{, _neo}/com/maidsmart/guide/GuideChaptersFlight.java`（巡逻航图：正文 1 处 + 新增 1 段）
- `promaid_src{, _neo}/com/maidsmart/guide/GuideChaptersWork.java`（酿造：正文 1 处 + 新增 1 段；排班表：正文 1 处 + 新增 1 段）——两树该文件改后仍**逐字节一致**
- `promaid_src{, _neo}/assets/promaid/i18n/en_us.json`：4 处键/值同步改写（含英文 value 去掉重复的材料句）+ 4 个新键（英文 value 的 `@说明` 写英文），6899 → **6903** 键
- 新配置项 0 / 新 mixin 0 / 配方 json 0 改动

## 验证
- 解码扫描（自写脚本，含三种转义形态）：4 条"写了合成但没图"全部变成 `token_nearby=YES`；2 件旧条目（武装拴绳 / 大运）不受影响。
- 断言脚本：4 个新键在两树 `en_us.json` 各恰 1 次、JSON 解析通过（6903 键）、4 处旧片段在 Java 与 en 两侧全部消失、两树 Work 文件仍逐字节一致。
- 两树 `javac EXITCODE=0`；四护栏回基线（2258 / FAIL0-WARN2 / FAIL0-WARN2 / 1261）。
- 打包 = 同号**第 14 次**（与 R851 同一份 jar，`a5a0a83e…d0a55d` / `4ac01070…8ecfbe`）；`lang json: OK`（`assets/**/lang/*.json` 语法）；`MISSING: none`。
- 未做：未部署（等开发者说「部署」）· 未实机翻手册 · 未写 changelog（等验收）。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）
- 状态：`未验收`。开发者翻 4 个章看合成表图与 JEI 一致、正文不再重复材料。

## 边界（没动什么）
- 不改配方本身（jar 内 `data/*/recipes` 不动）；不动 `GuideScreen` 的解析与分页逻辑；不动「功能速览」里提到巡逻航图的旧描述（那句属 R850 的精简范围）；不动物品 tooltip 里"合成：拴绳 + 木棍"那句（那是物品说明，不是手册章）。

## 台账
- 新增债 0。tag 材料在图里只能画代表物品（墨囊）这条近似，写进本记录的"改法"，不新增 TD。
