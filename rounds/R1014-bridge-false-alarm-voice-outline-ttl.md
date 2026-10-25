# 实测一千〇一十七 搭路误报修复 + 中性气泡补语音 + 金色描边 3 秒自动消失（1.3.3 第 147 次同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug（搭路误报与描边滞留都是既有行为回归；补语音是同一气泡的缺口修复）。
  一句话判定依据：没有新增配置项或能力，只是让既有搭路判定、描边同步和语音包回到正确行为。
- **开工前读过**：`rounds/R1013-cake-conflict-off-probe-removal-v133-release.md`（上一轮发布准备）、
  `promaid_src/com/maidsmart/task/BridgeUpBehavior.java`、`PlacedMarkNetworking.java`、
  `PlacedBlockTracker.java`、两树 `assets/promaid/voice/manifest.json`、`tools/voice/gen_voice_pack.py`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位误报来源 | 20 秒无放置 + `MaidPlaceGuard.blockedAtOwner` 拦下目标格时仍走“搭不上去”分支 | ☑ |
| 1 | 两树实现“挡位=等待” | 四个 `blockedAtOwner` 站点记录时刻；`canContinue` 近期被挡时保持运行、不播气泡 | ☑ |
| 2 | 补中性气泡语音 | 两树 manifest 新增 `bridge_cant_place.ogg`；TTS 合成、归一化、安装；语音表补日文台词 | ☑ |
| 3 | 描边视觉 TTL | `PlacedBlockNetworking` 增加 60 tick 到期表，每 tick 撤框；快照过滤；方块回收寿命不变 | ☑ |
| 4 | 两树编译 | `EXITCODE=0` | ☑ |
| 5 | 14 护栏 | `FAIL 0 / WARN 0` | ☑ |
| 6 | 双 jar + 专服回归 | Forge / Neo 构建成功；`test_server` 两套 + `test_maid_load` 均 PASS | ☑ |
| 7 | 部署 4 实例 | `match=True` ×4 | ☑ |
| 8 | 更新发布说明 | 桌面 2 份 + 仓库 3 份补上“描边 3 秒自动消失 / 挡位等待 / 气泡语音” | ☑ |
| 9 | 开发者实机验收 | 空旷平台不再误报；气泡有语音；描边放完很快消失 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法：①在超平坦或空旷平台让女仆搭路/搭高，主人站在她附近或目标格上方，观察 20 秒内是否还会蹦出“搭不上去，我先下来……”；②若真出现该气泡，确认有语音；③女仆放方块后看金色线框是否约 3 秒内消失，同时方块本身仍按原寿命回收。

## 需求 / 来源

- 开发者原话（本轮新增）：「1，“搭不上去，我先下来“没语音，而且明明是超平坦平台，为什么会报这个语音呢？
  2.女仆搭方块产生的黄色光标会有一定程度上的滞留。总是要隔上一段时间才会消失，而不是放快1小时它就消失。
  这样子观感不好。」

## 评估

### 1. 超平坦为什么也会报“搭不上去”

`BridgeUpBehavior.canContinue` 的失败判定只看两件事：`dyNow >= 1` 且距上次成功放置超过 400 tick（20 秒）。
它没有区分“为什么放不出去”。四个放置分支（空中平桥、下行台阶、斜上台阶、原地垫高）在取料前都会问
`MaidPlaceGuard.blockedAtOwner`：目标格的整格碰撞箱只要与**主人或其他女仆**的身体/头部相交，就跳过这一格。
这是防把主人埋住的安全约束，本身正确；但如果主人恰好站在目标格上方/旁边，女仆会连续 20 秒“有材料、有空间、
但目标格被占”，旧逻辑就把它当成“搭不上去”并播气泡。超平坦没有任何地形阻挡，所以看起来像误报。

**修法**：在四个 `blockedAtOwner` 拒绝点记录 `lastOwnerBlockedGameTime`；`canContinue` 的 20 秒分支先看
“最近 1 秒是否因挡位被拦”——是就保持行为运行、继续等对方挪开，不播失败气泡；`stopReason` 增加
`owner-blocked` 诊断原因。这样既不误报，也不会在主人暂时挡住时放弃搭路。

### 2. 中性气泡没有语音

语音包 manifest 里只有“搭不上去了（头顶被挡住）”对应的 `bridge_cant_climb.ogg`；中性的
“搭不上去，我先下来……”没有任何条目，命中不到音频。**修法**：两树 manifest 增加
`bridge_cant_place.ogg`（`contains`，排在头顶那条之后），日文台词
「ここには積めないみたい、先に降りるね」，走 `work` 情绪档；用本机 GPT-SoVITS 合成后按既有流程
峰值归一化到 -1.5 dB、编码 ogg q4 并安装进两树。

### 3. 金色描边为什么滞留

描边同步只在 `PlacedBlockTracker.track()` 登记、在 `untrackAny` / 到期回收 / 强制回收时撤销；也就是说
**线框的寿命等于方块登记的寿命**（搭路默认几十秒到几分钟，且女仆/主人靠近还会不断续命）。视觉上就成了
“方块放完很久框还在”。**修法**：把“视觉 TTL”从“方块寿命”里拆出来——`PlacedMarkNetworking` 维护逐维度
到期表，`noteAdd` 时记 `now + 60 tick`（约 3 秒），每 tick 扫描到期并只发撤框包；进服/换维度快照也按到期表
过滤。方块本身仍由 `PlacedBlockTracker` 按原寿命回收，二者互不影响。

## 改动文件

- `promaid_src/com/maidsmart/task/BridgeUpBehavior.java`（Neo 镜像同改）：
  `lastOwnerBlockedGameTime` 字段、四个拒绝点记录、`ownerBlockedRecently` 判定、`canContinue` 等待分支、
  `stopReason` 的 `owner-blocked`。
- `promaid_src/com/maidsmart/task/PlacedMarkNetworking.java`（Neo 镜像同改）：
  `OUTLINE_TICKS = 60`、`OUTLINE_UNTIL` 到期表、`tick(server)`、`note()` 登记/撤销、`pushSnapshot` 过滤、
  `clearAll` 清表。
- 两树 `ProMaidExtension.java`：服务器 tick 末尾调用 `PlacedMarkNetworking.tick(server)`。
- 两树 `assets/promaid/voice/manifest.json`：新增 `bridge_cant_place.ogg` 条目。
- `tools/voice/gen_voice_pack.py`：`work` 档与日文台词表补 `bridge_cant_place.ogg`。
- 新增音频：`promaid_src/assets/promaid/voice/bridge_cant_place.ogg`、
  `promaid_src_neo/assets/promaid/voice/bridge_cant_place.ogg`（30,781 字节，3.66 秒源 wav → 归一化 ogg）。
- 发布说明 5 份同步补三条行为描述。

## 验证

- 两树编译：`EXITCODE=0`。
- 14 护栏：`check_arch` / `check_bubble_i18n` / `check_catch_swallow` / `check_comment_dup` /
  `check_comment_style` / `check_dev_voice` / `check_guide_i18n` / `check_i18n_keys` /
  `check_mixin_targets` / `check_panel_coverage` / `check_preset` / `check_reflection` /
  `check_tree_mirror` / `check_workflow` 全部 `FAIL 0 / WARN 0`。
- 双 jar：
  - Forge `promaid-1.3.3-forge-1.20.1.jar` 18,042,723 字节
    `32fe567af33606a0a7c4514183d10f4d1b3d9088cb4e852ff49542ba0895d0d1`
  - Neo `promaid-1.3.3-neoforge-1.21.1.jar` 18,090,141 字节
    `4a981502176c826ada06d37e1bee1d6929aec577dd14f2855aa1d06d1fb44cfd`
  - 两个 jar 内均含 `assets/promaid/voice/bridge_cant_place.ogg` 与 manifest 条目；
    `BridgeUpBehavior.class` 含 `lastOwnerBlockedGameTime`，`PlacedMarkNetworking.class` 含 `OUTLINE_UNTIL`。
- 专服回归：`test_server.py 1201` PASS、`test_server.py neoforge1211` PASS、
  `test_maid_load.py neoforge1211` PASS（Done + 30 秒观察无延迟崩溃）。
- 部署：4 实例全部 `match=True`（Neo 21.1.250 / Forge 47.4.10 / 龙之冒险 v2.4a / pack1201）。

## 边界

- 只改“搭路 20 秒无放置”的判定分支：新增的是“被主人/其他女仆挡位时等待”，不改搭路启动条件、收尾距离、
  选材、危险格拦截和方块回收寿命。
- 不改变 `MaidPlaceGuard.blockedAtOwner` 本身的安全约束——仍然不会把方块放进主人或其他女仆的身体/头部格。
- 描边 TTL 固定 60 tick，不新增配置项；关掉「金色描边」开关时仍然一个包都不发。TTL 只撤视觉线框，
  方块本身仍按 `PlacedBlockTracker` 的既有寿命回收。
- 语音只新增 `bridge_cant_place.ogg` 一句；既有条目的文本、匹配顺序和音频文件不动。
- 本轮不触碰装备同步包、蛋糕冲突探测、发布平台操作；上一轮 R1013 的修复与发布文案保持有效。

## 验收

- 待开发者实机验收：超平坦不再误报、气泡有语音、描边约 3 秒自动消失。
- 上一轮 R1013 的装备 / 蛋糕验收项仍有效；本轮的 147 次重打只增补上述三项行为，不改动装备复制与蛋糕冲突逻辑。
