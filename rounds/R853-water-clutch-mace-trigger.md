# 实测八百五十三 · 落地水触发改成重锤/空袭同款落点帧（两树）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug（玩家反馈「落地水的触发不是很灵敏」= 已交付行为不达预期）。
  判定依据：不是加新功能，是同一功能（通用落地水/落地雪）的**触发判据**失效。
- **开工前读过**：两树 `combat/WaterClutchBehavior.java`（通用分支 vs 空袭/重锤分支的判据差异）·
  `config/MaidSmartConfig.java` 的 4+4 项配置（触发高度/保持时长/下探格数 × 水雪）·
  `config/PromaidConfigScreen.java` 的落地缓冲面板行 · `tool/MaidPlaceGuard.java`（**同一项配置的第二个消费者**：
  「深坠落阈值 ≥ 落地水触发高度」）· `guide/GuideChaptersFlight.java` 的「被动技能」两段 ·
  `ARCHITECTURE-SWITCHES.md` 的落地水几行 · `tools/verify/check_comment_style.py`（LONG-BLOCK 规则：
  类头注释 > 15 行、成员注释 > 8 行各计一处 —— 基线 2258，**本轮不许增加**）·
  `rounds/RELEASES.tsv`（现字节 = 第十五次同号重打）。
  结论：不是已记的债；`ARCHITECTURE-SWITCHES.md` 里没有等价开关（`combat.waterClutch` 只管开关，不管判据）。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 侦察 | 定位通用分支/空袭分支/重锤落地帧三处判据，写出差异表与失效根因 | ☑ |
| 1 | 实现 | 两树 `WaterClutchBehavior` 通用分支改落点帧（落差阈值降级为最小高度闸；加同一道 2 秒 CD） | ☑ |
| 2 | 文案 | 配置注释 + 面板行 + `ARCHITECTURE-SWITCHES.md` + 手册「被动技能」两段 + 两树 i18n（键与值） | ☑ |
| 3 | 编译 | 两树 `javac EXITCODE=0` | ☑ |
| 4 | 护栏 | `check_comment_style` 不高于 2258 / `check_preset` FAIL 0 / `check_workflow` FAIL 0 / `check_changelog` 不变 | ☑ |
| 5 | 打包 | 同号第 16 次：`MISSING: none` + jar 自检 + `RELEASES.tsv` 留痕 | ☑ |
| 6 | 交付 | 交开发者进游戏实测（**不部署、不回归**，等开发者说「部署」） | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = 让女仆背包放水桶，从 10+ 格高处落下若干次
  （含快速坠落/被击退横移），看她是否**每次都在落地前放水**；`logs/promaid.log` 搜 `clutch trigger`
  看 `fallDist` 与 `land`。

## 需求 / 来源

开发者当场反馈（原话）：

> 我发现落地水的触发不是很灵敏。我觉得应该都改成重锤模式的同款落地水。

## 评估

- **级别**：P2（保命被动技能偶发失效，不崩不卡；玩家可感知）。
- **失效根因**（对照两树代码逐条读出来的）：通用分支唯一的时机判据是
  `fallDistance >= 触发高度（默认 4.0）` **并且** `findLandingPos` 在
  `下探格数`（默认 2 格，上限 7）内找到"实心块 + 上方一格是空气"。而坠落是逐 tick 积分：
  快落时单 tick 位移可以超过这个 2 格窗口 —— 于是地面要么还在窗口外（返回 null），要么
  这一 tick 已经触地、`fallDistance` 被原版清零（`onGround` 同时成立），**窗口一次都没被采样到**，
  全程不放水。空袭/重锤分支之所以稳，是它不问 `fallDistance`，改成问
  「未落地 + 正在下坠 + 脚下 1~2 格内有非空气」的**落点帧**——这个状态在触地那一 tick 必然成立
  （碰撞把实体停在最后一个空气格里），所以每次都能抓住。
- **同一项配置的第二个消费者**：`MaidPlaceGuard`（悬空禁搭方块）用它当"深坠落阈值"。所以这项配置
  不能删、语义也不能变，改成"最小坠落高度闸"与它现有口径一致。
- **重锤/空袭分支不动**：它是 `isFlightAirborne` 那一条，语义是"飞行期保住 fallDistance 给收翅猛击"，
  本轮只把**通用那一条**换成同款判据。

## 改法

1. **两树 `WaterClutchBehavior` 通用分支**：时机判据换成落点帧
   （`!onGround` + `竖直速度 < 0` + `isNearGround`），`fallDistance >= 触发高度` 降级为
   **最小坠落高度闸**（防小跳/平时走路误放），并接入与空袭/重锤同一道 `FLIGHT_CLUTCH_CD`（2 秒）
   防"落进细雪 onGround 恒不成立 → 无限重放"（那正是空袭分支当年加 CD 的原因）。
   保持时长仍走 `combat.waterHold` / `snowHold`（配置不失效，不改成"接住就收"）。
2. **不新增配置项、不改默认值、不改存档/配置格式**：只改判据与文案。
3. 文案四处（都按"只讲现在是什么样"写）：
   - 两树配置注释（`COMBAT_WATER_FALL_DISTANCE` / `COMBAT_SNOW_FALL_DISTANCE`）；
   - 两树配置面板行说明（`PromaidConfigScreen` 的 `NumRow` 描述串）；
   - `ARCHITECTURE-SWITCHES.md` 两张表的对应行（**该文件是自动生成的**：只改配置注释，
     再重跑 `python tools/verify/gen_switch_inventory.py --write`，手改会被覆盖且 `check_workflow` 会报「已过期」）；
   - 两树手册「被动技能」①落地水那句（顺手修掉「默认 3.0 格」这个与配置默认值 4.0 不符的旧数字）
     与"细节参数"那段（说清触发高度现在只是"最少坠够多少格"）。

## 触及文件

- 两树 `com/maidsmart/combat/WaterClutchBehavior.java`（判据 + 类头触发条件清单，**保持注释块行数不增**）
- 两树 `com/maidsmart/config/MaidSmartConfig.java`（2 条 comment）
- 两树 `com/maidsmart/config/PromaidConfigScreen.java`（2 条面板说明）
- 两树 `com/maidsmart/guide/GuideChaptersFlight.java`（被动技能 arg2 与 arg5）
- 两树 `assets/promaid/i18n/en_us.json`（同上两段的 key 与英文 value）
- `ARCHITECTURE-SWITCHES.md`（由 `gen_switch_inventory.py --write` 重新生成）、
  `rounds/RELEASES.tsv`（第十六次字节 + 作废旧字节）、本轮记录
- `机制详解.md`（`WaterClutchBehavior` 那条的触发清单）
- **未动**：`MaidPlaceGuard`（只读它的口径）、`MaidMaceSmashBehavior`、任何开关默认值。

## 验证

- 两树 `javac` `EXITCODE=0`（Forge 463 源 / Neo 466 源）。
- 四护栏与基线一致：`check_comment_style` 929 文件 / **2258 处**（`{'LONG-BLOCK': 2258}`，改前改后同数）·
  `check_preset` FAIL 0 / WARN 2 · `check_workflow` FAIL 0 / WARN 2（登记第十六次字节后）·
  `check_changelog` 1261 处（本轮未动 `CHANGELOG.md` / `store/version_changelog.md`，数字不变）。
- 两树一致性：被动技能两段（落地水那句 + 细节参数那段）解码后两树逐字节相同；i18n 的**键**与 Java 段落
  逐字节一致（脚本断言：改后的段落能在 Java 各段落集合里精确命中、旧段落已不存在；JSON `json.loads` 通过）。
- 打包（同号**第十六次**）：`MISSING: none`、`lang json: OK`、`verify_jar_classes` OK
  （Forge 760 / Neo 764 class 全在、mixin 登记 96/95 无遗漏、注入签名无歧义）；
  forge `0e922cb8…b01b27`（17,628,022 B）、neo `22b41e3b…ba822a`（17,666,845 B）；桌面快照已刷新。
- jar 内自查：手册新句「在即将落地那一瞬自动放水缓冲」在、旧句「坠落高度超过阈值」不在；
  配置新注释「最小高度闸」在；i18n 英文新句在。
- **反证**（没法在本轮跑运行时的部分说清楚）：旧判据的失效路径是「快落时单 tick 位移 ≥ 下探窗口
  （默认 2 格，上限 7）→ 地面既不在窗口内、也没等到下一次采样就已经触地（`onGround` 成立、
  `fallDistance` 被原版清零）」，新判据用的落点帧（脚下 1~2 格内有地面）**在触地那一 tick 必然成立**
  （碰撞会把实体停在最后一个空气格里）——这正是空袭/重锤那条链路一直稳的原因。
  真正的运行时证据要等开发者进游戏实测（多次高处坠落 + 看 `logs/promaid.log` 的 `clutch trigger`）。
- **未做的验证**：未部署、未跑专服回归、未进游戏实测（等开发者开闸「部署」）。
- 形态说明：落地水那句（arg2）因新句子变长，从 1 行折成 21 行字符串拼接（该文件本来就有大量多行拼接写法，
  风格一致）；「细节参数」那段 5 行 → 8 行。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）

- 状态：`未验收`（需开发者进游戏实测多次坠落）。

## 边界（没动什么）

- 不改开关默认值、不加新配置项、不动存档与 toml；不改空袭/重锤分支；不改 `MaidPlaceGuard`；
  不动 `CHANGELOG.md` / `store/version_changelog.md`（§6.1 验收后才写）；不部署、不回归（等开发者开闸）。

## 台账

- 无新增债。落地水判据的旧写法（依赖 fallDistance 时机）算本轮清掉的行为债，不单独建条目。
