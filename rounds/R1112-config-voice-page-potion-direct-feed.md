# R1112 气泡开关归位 + 药水直喂 + 发布声明工作流

## 排班

- **跑道**：A 修 bug（配置页归位 / 投喂语义）+ 文档流程（发布声明覆盖表）。
- **开工前读过**：`PromaidConfigScreen` 的 `voiceRows` / `scheduleRows`，
  `MaidAidOwnerBehavior.giveDrinkablePotion` 与姐妹链 `feedDrinkablePotionTo`，
  `EmotionalActionExecutor.feedFoodDirect`，`WORKFLOW.md` §6.1/§6.3，`rounds/RELEASES.tsv` 的列格式。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 排班气泡开关从排班页挪到语音页 | 与「气泡与系统消息」总开关同页 | ☑ |
| 1 | 总开关补显式面板行 | 配置页能直接开关，不再只靠 toml | ☑ |
| 2 | 饮用型药水改为直接喂给主人 | 效果立刻生效，不再进主人物栏 | ☑ |
| 3 | 药水气泡/系统消息/语音同步 | 中文、英文、日文语音三处一致 | ☑ |
| 4 | 写「发布声明不遗漏」工作流 + 收集脚本 | §6.4 可照做，脚本能写/校验覆盖表 | ☑ |
| 5 | 编译/护栏/打包/部署/测试 | 两树 OK；护栏全绿；四套 PASS；4 实例 match=True | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

"排班语音气泡及语音是否开启也作为单独的一个配置项，和那个总的配置开关一起放语音配置里

为'怎么写新版本的release声明写一个工作流，保证更新内容不遗漏"

"顺便女仆投喂主人药水也改为和投喂其他生物一样是直接生效而非放入物品栏（文本和语音也要改了）"

## 评估

1. **配置项归位**：`misc.scheduleBubbleEnabled` / `misc.scheduleBubbleDist` 之前挂在「排班」页，
   而它们控制的是"贴身气泡与语音"；总开关 `misc.promaidHints` 反而没有显式面板行。
   用户要的是"三个气泡相关项在同一页"，因此把 `voiceRows()` 末尾加一个「气泡与提示」小节，
   总开关 + 排班气泡开关 + 触发距离一起列出，并从 `scheduleRows()` 删除旧的两行 `cvAuto`。
2. **药水直喂**：主人链原来把饮用型药水塞进快捷栏/背包，再让主人自己喝；姐妹链早就是
   `finishUsingItem` 直接生效。用户要求统一成"喂其他生物"的语义，所以主人链也改为以主人为
   target 调物品自己的 `finishUsingItem`。喷溅/滞留仍走投掷，牛奶/蜂蜜仍走原路径，不动。
3. **空瓶归属**：原版 `PotionItem.finishUsingItem` 对 `Player` target 会自己把玻璃瓶放进玩家
   物品栏，因此主人链不能再补一个瓶子（否则双瓶）；只有物品返回非空栈（创造模式原栈 /
   模组自带容器）时才还回女仆背包，背包满则落地。
4. **文案/语音绑定**：气泡从"放你背包了，快喝"改成"直接喂你喝"，manifest 的匹配文本、
   日文台词和 ogg 必须一起换；否则会出现"显示新词、播旧音"（§6.3 的硬性要求）。
5. **发布声明漏项**：`RELEASES.tsv` 已有每轮的详细备注，轮次记录里有「需求来源 / 改动」，
   但此前没有把两者强制对齐的步骤。新增脚本以 `RELEASES.tsv` 为最小单位生成覆盖表，
   再用 `--check` 卡住"没填落点 / 还有 TODO"。

## 改动

### 配置页（`PromaidConfigScreen`，两树）

- `voiceRows()` 末尾新增「气泡与提示」小节：
  - `气泡与系统消息（总开关）` → `MISC_PROMAID_HINTS`；
  - `排班贴身气泡与语音` → `MISC_SCHEDULE_BUBBLE_ENABLED`；
  - `排班气泡触发距离（格）` → `MISC_SCHEDULE_BUBBLE_RADIUS`。
- `scheduleRows()` 删除原来的两条 `cvAuto(...)`（同一项不再在两个页面重复出现）。
- 语言文件：`config.promaid.misc.scheduleBubbleEnabled` 改为「排班贴身气泡与语音」/
  `Schedule bubbles & voice`；`scheduleBubbleDist` 改为「触发距离」口径。

### 药水直接喂（`MaidAidOwnerBehavior`，两树）

- `giveDrinkablePotion` 不再 `giveToOwnerHotbarFirst` 塞背包，改为：
  1. 从女仆背包取 1 瓶（`extractItem`，净消耗）；
  2. 快照 `drinkShow` 用于 CD、举瓶动画与日志；
  3. `taken.getItem().finishUsingItem(taken, owner.level(), owner)`——效果、音效、统计按原版
     完整路径即时生效；
  4. 返回栈非空才还回女仆背包（创造模式原栈 / 模组容器），满则 `spawnAtLocation` 落地；
     原版药水对玩家已自动给空瓶，这里不再补；
  5. 挥手 `swing`、`BombPose.showGated` 举瓶、`entity.generic.drink` 音效；
  6. 气泡「主人，药水我直接喂你喝啦～」，系统字幕「女仆喂你喝了<label>药水」。
- 删除"主人背包满了，药水放不进去"分支（直喂不再依赖主人物品栏空间）。
- 顶部流程注释、`HEAL_POTIONS` 注释、姐妹链的"主人链只塞背包"旧描述同步改写。
- 喷溅/滞留投掷、牛奶/蜂蜜、食物直喂路径均未改。

### 文案 / 语音 / i18n

- 两树 `assets/promaid/voice/manifest.json`：`aid_potion_given.ogg` 的匹配文本改为
  「主人，药水我直接喂你喝啦～」（`contains`）。
- `tools/voice/gen_voice_pack.py`：`aid_potion_given.ogg` 日文改为
  「ご主人様、お薬は直接飲ませてあげますね～」（care 档）。
- 重新合成 1 条 → 归一化（+9.10 dB，-10.60 → 目标峰值）→ 两树各安装 1 条。
- 两树 `assets/promaid/i18n/en_us.json`：
  - 新增「主人，药水我直接喂你喝啦～」英文；
  - 修正既有错位：1779–1793 行的药水/金苹果/牛奶等译文与键整体错位（英文玩家会看到串行提示），
    按中文键逐条校正。

### 发布声明工作流（`WORKFLOW.md` + `tools/release/`）

- 新增 §6.4「发布声明：按覆盖表逐轮交代（保证不遗漏）」：枚举 → 逐行判断玩家可感知 →
  补配置/语音/兼容/升级四类来源 → 固定骨架 → `--check` 放行 → 常见漏项对照。
- 新增 `tools/release/collect_release_rounds.py`：
  - 从 `RELEASES.tsv` 取某版本的全部登记行，按轮次标签分组；
  - 有 `R####` 的读 `rounds/R####-*.md` 的「需求来源 / 改动」；
  - 老行没有 `R####` 时用「实测NNN」反查轮次记录，查不到则回退 RELEASES 备注；
  - `--write` 生成覆盖表（一行一轮、落点列 TODO），`--check` 校验每轮都出现且无 TODO。
- 覆盖表归档在 `rounds/RELEASE-<ver>-coverage.md`，作为"这句为什么在 / 不在"的证据链。

## 验证

- 两树编译 `EXITCODE=0`。
- 14 项护栏 `FAIL 0 / WARN 0`（`check_comment_style: PASS`，注入点 293）。
  其中 `check_panel_coverage` 初报陈旧，重跑 `gen_panel_coverage.py` 后
  Forge 619 / Neo 629 个已收录字段，`FAIL 0`。
- 新脚本自测：`collect_release_rounds.py 1.3.3` 列出 123 个轮次；
  `--write` 生成覆盖表；填掉 TODO 后 `--check` PASS；未填时按预期 FAIL 列出全部轮次。
- 语音：`gen_voice_pack.py run aid_potion_given.ogg` 生成 OK；`norm_voice_pack` 1 条 OK；
  `install_voice_pack` 两树各 185 条、`missing=none`；两树 ogg 同为 43,712 字节。
- 四套专服回归 PASS（1201 / neoforge1211 × server / maid_load）。
- Neo jar：18,204,585 字节，SHA256
  `4992E3957451B5BB98D0E165007080C99CE3B680C1ACE4DE0A71CEF7C06B1850`。
- Forge jar：18,143,487 字节，SHA256
  `F2C921AC57D795BA9A4DDF846420BC06A1D7AF936891CD1D1B75D6596E0E3ACD`。
- 已部署 4 实例，`match=True`。

## 边界

- **只有饮用型药水改直喂**：喷溅/滞留仍是投掷，牛奶/蜂蜜/食物路径不变。
- **空瓶**：原版对玩家 target 自己把玻璃瓶放进主人物品栏；创造模式下原栈返回，女仆背包会收回
  这瓶药（等于不消耗），这是原版 `finishUsingItem` 的语义，不再额外补瓶。
- **配置项**：`scheduleBubbleEnabled` / `scheduleBubbleDist` 只是换了显示页，键名、默认值、
  行为都不变；旧 toml 配置继续生效。
- **i18n 修正范围**：只动 `en_us.json` 的 1779–1793 这一段错位；其它语言文件未动。
- **发布声明**：本轮只落地流程与脚本，不写 1.3.3 的玩家可见发布声明——按 §6.1 等验收后
  再用 §6.4 的覆盖表逐轮生成。
- 版本号仍为 **1.3.3**，不升号。

## 验收

1. **配置页**：打开「语音」页，最下方能看到「气泡与提示」：总开关、排班贴身气泡与语音、
   触发距离三项；「排班」页不再重复出现后两项。关掉排班项后，靠近排班女仆不再出气泡/语音，
   但其它提示仍受总开关控制。
2. **药水直喂**：主人血量压到 30% 以下、女仆背包只放普通治疗药水——她走到身边做喂药动作，
   治疗立即生效；主人物品栏**不出现**这瓶药（只多一个空玻璃瓶），气泡说
   "主人，药水我直接喂你喝啦～" 并播放对应语音；系统字幕写"女仆喂你喝了治疗药水"。
3. **其它药水不变**：喷溅/滞留治疗仍按原样投掷；着火时的抗火、溺水时的水肺同样直接喂。
4. **发布声明流程**：验收后运行
   `python tools/release/collect_release_rounds.py 1.3.3 --write rounds/RELEASE-1.3.3-coverage.md`，
   按 §6.4 填完落点后 `--check` 必须 PASS，再据此写发布声明。
