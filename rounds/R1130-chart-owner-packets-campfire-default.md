# R1130 改包防护 / 航图开屏断线 / 营火烹饪默认值

## 排班

- **跑道**：C 功能修复 + 安全加固（玩家三条反馈；版本号仍 1.3.3，同号重打）。
- **开工前读过**：`PatrolNetworking` 全部 C2S 处理器与 `OpenPatrolPacket` 编解码、`PatrolChartData.canUse`、
  `PatrolChartItem`、`MaidCookBehavior` 的营火链路与 `cook scan` 诊断、`MaidSmartConfig` 的 INTERNAL 段、
  `ProMaidMod` 的一次性迁移族（`migrateDangerCampfire` / `migrateOreTable`）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 所有 C2S 航图操作校验署名 | 别人的署名航图：改书/绑定/连接/打点/飞过去/刷新一律拒绝 | ☑ |
| 2 | 修 1.20.1 右击航图断线 | `OpenPatrolPacket.decode` 的 length/seconds 按 double 读 | ☑ |
| 3 | 营火烹饪开关对老档默认开 | 一次性迁移把停在旧默认 false 的键改成 true | ☑ |
| 4 | 两树编译/14 护栏/双 jar/部署/四测试/轮次文档 | 全绿 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者，见文末「验收」。

## 需求 / 来源

1. 「先把改包的防护做了」——右键拦不住改造过的客户端，C2S 包才是真正可被构造的入口。
2. 「1.20.1，女仆在烧制状态下无视了营火（顺便这个开关应该顺便默认开启）」。
3. 「刚刚运行游戏，右击航图直接使得服务器连接丢失」。

## 评估

- **改包防护**：R1129 只在 `PatrolChartItem.use` 拦了右键，五个 C2S 处理器（`BookOp` / `BindMaid` /
  `Connect` / `StartMarking` / `MarkPatrolPoint` / `FlyToTarget` / `Request`）都只校验"手上有航图"，
  没校验"这航图是不是你的"。标记态还存在一个真实缝隙：标记态挂在玩家身上而不是航图上，
  玩家可以在自己的航图上点「开始标记」，再换手拿别人的航图继续打点。
- **断线**：`OpenPatrolPacket.encode` 写两个 double，Forge 侧 `decode` 却用了 `m_130242_()`（readVarInt）。
  没有标记点时 length/seconds 都是 0.0（全零字节），误读成两个 0 + 空串，恰好能通过；一旦打过点，
  长度/时长非零，错位后的字符串长度字节越界 → 客户端判定"服务器发来无效数据包"并主动断开
  （服务端日志里那条 `ClientboundDisconnectPacket` 的 ClassCastException 是断开动作的副产物）。
  与实机日志完全对得上：16:40:05 / 16:40:13 两次开屏成功（还没打点），16:41:06 打完点再开 → 断线。
- **营火**：`cook scan` 实机日志明确写着「营火开关=关 身上营火食材=有」。声明里 `cookCampfire`
  默认早已是 true，但老 toml 里保留着当年默认值的 false——声明改默认对老档无效，于是她只走炉子链路。

## 改动

### 航图改包防护（两树）

- `PatrolNetworking` 新增 `editableChart(player, offHand)`：先认包里那只手、再认另一只；
  不是航图 → "不在手上"；`canUse` 不通过 → "这本航图署的是「某某」的名字，只有原作者能改"。
- `BookOpPacket` / `BindMaidPacket` / `ConnectPatrolPacket` / `FlyToTargetPacket` 改用 `editableChart`。
- `StartMarkingPacket` / `MarkPatrolPointPacket`：解析到航图后立刻 `canUse` 校验，不通过就
  `PatrolMarking.stop` + 清客户端标记态，堵住"自己的标记态 + 别人的书"这条换手缝隙。
- `RequestPatrolPacket`：别人的航图连刷新包都不回（静默，避免 0.5 秒一次的提示刷屏）。

### 开屏断线（1.20.1）

- `OpenPatrolPacket.decode`：`m_130242_()`（readVarInt）×2 → `readDouble()` ×2，与 `encode` 对齐。

### 营火烹饪默认值（两树）

- `MaidSmartConfig.INTERNAL_SPEC` 新增 `cookCampfireMigrated`（一次性标记）。
- `ProMaidMod` 新增 `migrateCookCampfire()`：标记未置位时，把仍是 false 的
  `MISC_COOK_CAMPFIRE` 改成 true 并写日志；标记落盘后不再反复打开——玩家想关仍可在面板关掉。
- 四个语言文件补 `config.promaid.misc.cookCampfireMigrated`。

## 验证

- 两树编译 `EXITCODE=0`。
- 14 项护栏 `FAIL 0 / WARN 0`（含 `check_workflow`、`check_dev_voice`、`check_tree_mirror`）。
- 打包 + 部署 4 实例 `match=True`。
- 四套专服回归 PASS：`test_server 1201`、`test_server neoforge1211`、
  `test_maid_load 1201`、`test_maid_load neoforge1211`。
- Forge jar：18,206,037 字节，SHA256
  `37B7005A186A8128300033B6FA04BFFF2D3CBF4B892A982F8A4AA6B8C476E7E4`。
- Neo jar：18,267,205 字节，SHA256
  `F5E52737CAB4DB3D5696D053EE0AB437649671C072D58EB876583D6C902E7993`。
- **反证**：
  - 未署名的航图 `canUse` 恒 true——新做的书、旧档没打过点的书一切操作与之前完全一致。
  - 署名校验只加在"改/读这本航图"的入口；女仆绑定数据、巡逻飞行、排班链路一个字节都不动。
  - 迁移只搬"还停在旧默认 false"的键；已是 true 的只落标记，不写值。
  - `OpenPatrolPacket` 的 wire 格式没有变，只修了解码；Neo 侧本来就是 `readDouble`，格式一致。

## 边界

- 署名按玩家 UUID 判定；离线/换号无法通过（预期）。未署名的航图仍是谁都能用（没打过点=普通物品）。
- 迁移标记在 `promaid-internal.toml`；迁移后玩家手动关掉营火烹饪不会再被打开。
- 营火烹饪仍受工作区、搜索半径（默认 16 格）、垂直范围（±4）、点着状态、四位空位约束；
  日志搜「cook scan」可看到具体原因。
- 版本号仍为 **1.3.3**，同号重打；发布素材等验收后再写。

## 验收

1. 用两个客户端（或改造客户端发 `BookOpPacket`）拿别人署名的航图：改名/删轨道/绑定/连接/飞过去/打点全部被拒，提示「只有原作者能改」。
2. 自己先进标记态、再换手拿别人的航图按中键：提示「署的不是你的名字」，标记态结束，对方航图不变。
3. 1.20.1 手拿航图，先打 1~3 个点，再右键开屏：界面正常打开，不再掉线。
4. 老存档直接进游戏（不打开面板）：`latest.log` 出现「营火烹饪开关的旧默认是关：本次已改为开」，
   面板里「营火烹饪」为开；旁边点着营火且她身上有生肉时，她改去营火边摆食材（日志 `cook switch` / `cook scan`）。
5. 面板里把「营火烹饪」关掉并重启：保持关闭，不再被迁移打开。
