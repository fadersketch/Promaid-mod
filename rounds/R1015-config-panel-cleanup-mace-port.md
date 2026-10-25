# R1015 配置面板收尾 + 原版名称 + 1.20.1 重锤移植（1.3.3 第 148 次同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug + B 小功能（保存按钮与矿表旧输入框属于“残留交互”清理；名称显示是既有显示缺陷；
  重锤是 1.21.1 既有能力向 1.20.1 的移植，不新增玩法框架）。
- **开工前读过**：`rounds/R1014-bridge-false-alarm-voice-outline-ttl.md`、两树 `PromaidConfigScreen.java`、
  `MaidItemNames.java`、`BlueprintNames.java`、Neo `MaidMaceSmashBehavior.java`、`MaidSmartConfig.java`、
  `ProMaidExtension.java`，以及「试炼密室」回移植 jar 的物品/音效注册名。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 去掉「保存并返回」 | 两树 `bottomButtons` 不再加按钮；ESC / 返回路径统一走 `m_7379_` / `onClose` 保存；失败提示改为“按 Esc 关闭会自动保存” | ☑ |
| 1 | 删矿表旧输入框 | 两树删除 `minableInput` / `lockedOreId` / `addMinable` / 回车提交 / 黄框红字；点图标直接加入，右上角小叉移除，回退快照保留 | ☑ |
| 2 | 原版物品名 | 两树新增 `MaidItemNames.name(String)`；配置面板所有物品/方块显示改用原版悬浮名（未知 id 回退手写词表）；搜索仍同时匹配本地化名与注册名 | ☑ |
| 3 | 重锤移植 | Forge 新增 `MaidMaceSmashBehavior`，按 `trials:mace` / `trials:wind_charge` 注册名识别；4 条配置项 + 战术页面板行 + 中英译文 + brain 235 注册 | ☑ |
| 4 | 两树编译 | `EXITCODE=0` | ☑ |
| 5 | 14 护栏 | `FAIL 0 / WARN 0`；面板覆盖、预设基线、开关清单同步 | ☑ |
| 6 | 双 jar + 专服回归 | Forge / Neo 构建成功；`test_server` 两套 + `test_maid_load` 均 PASS；Forge 额外带 Trials 启动 PASS | ☑ |
| 7 | 部署 4 实例 | `match=True` ×4 | ☑ |
| 8 | 更新发布说明 | 桌面 2 份 + 仓库 3 份追加本轮条目 | ☑ |
| 9 | 开发者实机验收 | 面板无保存按钮、矿表点图标即加入、悬浮名为中文、1.20.1 持试炼重锤会起跳猛击 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法：①配置面板任意改动后直接 ESC，退出再进确认已保存；
  ②矿表搜索并点击方块图标，确认不再出现重要度输入框、名单立即多一行，右上角小叉可移除；
  ③悬停 `stripped_warped_hyphae` 等方块，确认第一行是中文名、第二行仍是注册名；
  ④1.20.1 整合包里给女仆主手试炼重锤 + 背包风弹，靠近怪确认起跳、下落猛砸、有音效与粒子。

## 需求 / 来源

- 开发者原话（本轮）：
  1. 「我发现手册的保存并退出其实是一个废按键。玩家使用ESC退出或者直接返回配置，其实都会做到保存并退出的效果。」
  2. 「还有残余，如图一，玩家选中哪个矿物的时候仍然会冒出旧版本，用来填重要度的输入框，把这个输入框去掉。」
  3. 「为什么在配置面板各种物体的名称里面显示的是这样的?有一些甚至是中英混合文字，而不是纯粹的中文。……
     为什么不能直接套用原版的物品栏名称与搜索的代码呢？」
  4. 「在刚刚运行的龙之冒险整合包里面，我看到了有人做出了移植版的重锤。看一下，能不能直接把1.21.1版本
     promaid里的重锤的相关参数和配置面板直接迁移过来。」

## 评估

### 1. 「保存并返回」为什么是废键

`m_7379_`（Neo 为 `onClose`）本来就是延迟提交的统一入口：ESC、目录跳转、手册返回、窗口关闭都会调用它。
底部按钮只是再调一次同一个方法，玩家反而会以为“不点就不保存”。本轮把按钮删掉，保留空方法避免十几处
调用点扩散改动；失败提示改成“改好后再按 Esc 关闭，会自动保存”。手册里所有“点保存并返回”的指引同步
改成“按 Esc 关闭（自动保存）”，酿造手册自己的保存按钮不动。

### 2. 矿表旧输入框

旧流程是“点图标 → 黄框锁定 → 下方出现重要度输入框 → 点添加/回车”。数值早已废弃（内部用占位值），
输入框只剩误导。本轮删除 `minableInput`、`lockedOreId`、`addMinable()`、回车提交、锁定黄框与闪烁红字；
点击图标时若不在名单则直接 `setOreValue(id, TABLE_ENTRY_PLACEHOLDER_VALUE)` 并压回退快照，右上角小叉
仍调用 `removeOre`。木材表共用同一套逻辑。

### 3. 名称中英混合

面板显示走的是 `BlueprintLib.cnName` 手写词表；词表没收录的 id（如 `stripped_warped_hyphae`）原样返回路径，
于是第一行是英文路径、第二行又是注册名、第三行“未添加”，看起来中英混杂。本轮在 `MaidItemNames` 增加
注册名入口：按 `items` 注册表构造 `ItemStack`，取 `getHoverName()`（1.20.1 为 `m_41786_`）的当前语言名；
物品不存在再查方块名，最后才回退手写词表。创造网格的搜索缓存本来就用同一套名字构建，所以中文搜索、
英文 id 搜索都继续有效；第二行注册名保留，方便填配置。

### 4. 1.20.1 重锤移植

「试炼密室」回移植把重锤做成了 `trials:mace`（`TieredItem` + `CriticalHitEvent` 给玩家加下落伤害），
但它的猛击只处理玩家，生物路径没有加成。本轮把 1.21.1 版 `MaidMaceSmashBehavior` 的行为原样搬到 Forge：

- 触发：主手重锤、有目标、距离 ≤ 起跳距离、站在实地、冷却已过；开启“必须消耗风弹”时还要背包/副手有
  `trials:wind_charge` 并消耗 1 枚。
- 起跳：水平 0.15、垂直 1.2（风弹 1.7），CLOUD + EXPLOSION 粒子与 `trials:wind_charge` 音效。
- 落地：目标仍在 4 格内则结算——基础攻击力 + 附魔 + 1.21.1 原版三段下落加成
  （4 / 12+2×(f-3) / 22+(f-8)），补火焰附加、击退附魔、荆棘反击与耐久；随后清零坠落距离。
  目标半空被打死/跑开时，跃起结束也清一次坠落距离，避免按自起跳高度摔伤。
- 音效：按贴地/空中选 `trials:mace_smash_ground(_heavy)` / `trials:mace_smash_air`，缺失时回退原版暴击音。
- 落水/睡觉/骑乘/滑翔/空袭激活时不起跳；空袭自己的俯冲猛击优先。

配置面板“战斗与自保 → 单兵战术”新增 4 行：重锤猛击、必须消耗风弹、冷却（tick）、起跳距离（格），
默认值与 1.21.1 一致。物品与音效全部按注册名查，未安装带重锤的模组时行为不会启动，也不会硬依赖 Trials 类。

## 改动文件

- 两树 `com/maidsmart/config/PromaidConfigScreen.java`：底部按钮、矿表锁定/输入流程、显示名替换。
- 两树 `com/maidsmart/config/MaidItemNames.java`：新增 `name(String)`。
- 两树 `com/maidsmart/guide/GuideChapters*.java` + `assets/promaid/i18n/en_us.json`：手册保存措辞与矿表/伐木操作说明。
- `promaid_src/com/maidsmart/combat/MaidMaceSmashBehavior.java`（新增，仅 Forge）。
- 两树 `com/maidsmart/config/MaidSmartConfig.java`：Forge 新增 4 条重锤配置；Neo 原样保留。
- 两树 `com/maidsmart/config/PromaidConfigScreen.java`：Forge 新增 4 行面板；Neo 原样保留。
- `promaid_src/com/maidsmart/ProMaidExtension.java`：brain 235 注册重锤行为。
- 两树 `assets/maid_smart/lang/{zh_cn,en_us}.json`：4 条重锤译文键（Forge 新增；Neo 已有）。
- `tools/verify/check_preset.py`：Forge DIY 基线 108 → 110（两条默认开、随 DIY 关）。
- `promaid_src/assets/promaid/panel_coverage.txt`、`ARCHITECTURE-SWITCHES.md`：重新生成。

## 验证

- Forge / Neo 编译：`EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`；`check_panel_coverage` 596 个字段全收录；`check_guide_i18n` 缺译 0；
  `check_preset` Forge 110 / Neo 111 与基线一致；`check_workflow` 开关清单一致。
- 双 jar：Forge 18,051,913 字节（SHA256 `ca7321ed…4ebfcf3c`）；
  Neo 18,089,852 字节（SHA256 `d36b5801…57ec7d`）。
- 专服：`test_server.py 1201`、`test_server.py neoforge1211`、`test_maid_load.py neoforge1211` 均 PASS；
  Forge 测试服额外挂载 Trials 2.3.2 启动到 Done，无类加载/注册表错误（测完已移除）。
- 部署：4 实例 `match=True`。
- 待实机：配置面板交互、悬浮名语言、重锤起跳/猛击表现。

## 边界

- 重锤只识别 `trials:mace` / `trials:wind_charge`；其他模组的重锤不会触发（避免把普通武器误判成重锤）。
- 1.20.1 没有原版 `MaceItem`，伤害加成由本行为自己算；Trials 自带的玩家 `CriticalHitEvent` 仍只对玩家生效，
  两者不冲突。
- 名称显示使用客户端语言表；服务端/无语言表环境（专用服务器）不涉及面板渲染。
- 版本号仍为 1.3.3（同号重打），不升号。

## 验收

- 待开发者实机验收：配置面板无「保存并返回」按钮、ESC 后改动已保存；矿表点图标直接加入且无输入框弹层；
  悬浮名第一行为中文、第二行仍为注册名；1.20.1 整合包内持试炼重锤 + 风弹能起跳猛击。
- 上一轮 R1014 的搭路气泡 / 描边验收项仍有效；本轮不改动搭路判定、描边 TTL 与装备同步逻辑。
