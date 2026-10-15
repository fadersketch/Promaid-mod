# R975 · 女仆日记本（目录 + 跳转 + 逐日正文）

## 排班

1. 盘点现有日记数据源（多级记忆索引 `AiMemoryIndexStore.IndexRecord`）与现在唯一的展示方式（记忆页里 6 行拼串）。
2. 服务端：`collectDiary` / `sendDiaryResponse` + 日记请求/数据包（两树注册）。
3. 客户端：日记页（左目录 / 右正文 / 双向翻页）+ 记忆页第 4 个 tab。
4. 两树编译、14 护栏、双包、部署 4 实例、四件回归。
5. 台账与文案。

## 需求 / 来源

女仆日记此前只以「记忆」页里的一行行原文摘要存在（`[日记 第X~Y天] 正文` 拼在状态流中间，最多 6 篇、
与段落/关系混在一起）。诉求：日记要像**真正的日记**——有目录、能跳转、能一天一天看那天发生了什么。

数据其实早就有：`AiMemoryArchiver` 在跨日/三日/周/月边界（或玩家睡醒）时生成多级索引记录，
`IndexRecord` 带 `level`（日 / 3日 / 周 / 月）、`startDay`、`endDay`、`content`、`eventIds`。
缺的是**一个像日记的界面**，而不是数据。

## 改法

### 服务端 `build/BlueprintBookNetworking`

- `collectDiary(maid, level)` 输出结构化行（只走数据、不发原文以外的任何东西）：
  - `STAT|篇数|最小日|最大日`
  - `DIARY|级别|起日|止日|字数|正文`（按 `endTick` 倒序，最新在前）
- 清洗与封顶：正文里 `|` 换 `/`、去掉颜色码，单篇封顶 2000 字、最多 400 篇（包不能无限涨）。
- `sendDiaryResponse(player, maid, level, uuid)`。

### 服务端 `build/BlueprintBookEntityPackets`

- 新增 C2S `MaidDiaryRequestPacket`（权限同链路调试：主人或 OP）与 S2C `MaidDiaryResponsePacket`。
- 注册：Forge 走 `CHANNEL.registerMessage(29/30, …)`；Neo 走 `r.playToServer` / `r.playToClient` +
  `CustomPacketPayload`（含 `TYPE` 字段与 `type()` 重写）。

### 客户端 `build/BlueprintBookScreen`

- 记忆页第 4 个 tab **「日记」**。四个 tab 一组居中，总宽 46+50+46+50 + 三道 4px 缝 = **204**——
  与原来三个 tab 的外框**同宽**，因此不会去挤左侧「← 女仆列表」与右侧「清空记忆」。
- 新页 `renderDiaryView` / `diaryViewButtons`：
  - **左列目录**：一行一篇 `第 X~Y 天 · 日/3日/周/月记`，最新在前，选中的行前有 `▶`；条目多了可翻页。
  - **右列正文**：标题行 `第 X~Y 天 · 日/3日/周/月记 · N 字`，正文按窗口宽度**折行**再分页——
    长日记不会溢出屏幕，也不会被截断。
  - 两列各自一对 `◀ / ▶` 翻页键 + 页码，左右列几何由 `diaryLayout()` 一处算出（渲染与按钮同口径，
    页码不会和按钮错位）。
  - 解析防御式：`DIARY` 行字段不足或类型不对直接跳过，坏数据不会把整页打崩。
- 换女仆 / 返回女仆列表 / 清缓存三处，`clearDiary()` 与 `clearGraph()` 一起走（旧日记不会串到新人身上）。
- 空状态：「她还没有日记——睡一觉或跨天后，她会把这段时间的事整理成日记」。

## 触及文件

- `promaid_src` / `promaid_src_neo`：`com/maidsmart/build/BlueprintBookNetworking.java`、
  `com/maidsmart/build/BlueprintBookEntityPackets.java`、`com/maidsmart/build/BlueprintBookScreen.java`（两树同改）。
- 文档：`rounds/R975-maid-diary-book.md`、`rounds/RELEASES.tsv`、`DEV_GUIDE.md`、四份发布文案、`机制详解.md`。

## 验证

- 两树 `javac`：forge `EXITCODE=0`、neo `EXITCODE=0`。
  首轮 neo 两处问题已修：`sendDiaryResponse` 被插了两份（CHANNEL 版与 PacketDistributor 版）→ 删掉前者；
  客户端日记方法块漏了官方名转换（`to_neo` 只作用到了 tab 条上）→ 全量转换后复查无 SRG 残留。
- 14 护栏全 `FAIL 0`（`check_comment_style` `RESULT: PASS`；`check_tree_mirror` 只报告不判门）。
- 包体：forge **17,661,250** 字节 `67182071…cf11cc`；neo **17,708,952** 字节 `a22ff6f2…cae2cc`。
- 部署：`deploy_all.py` 4 个目标全 `match=True`（`elevated rc: 0`）。
- 回归：`test_server.py` / `test_maid_load.py` 两加载器各两件，四件全 `verdict: PASS`。

## 验收

手册 → 女仆记忆 → 选女仆 → **日记** tab：

1. 顶部写成 `女仆日记 · <名字>（共 N 篇）`；没有日记时给出「睡一觉或跨天后…」的空状态说明。
2. 左列列出目录（最新在前），行文形如「第 12~12 天 日记」；点任意一条 → 右侧立刻切成那天的正文，
   正文页码回到第 1 页。
3. 选中的目录行前有 `▶`；条目多时左列出现 `◀ / ▶` 与「第 x/y 页」，翻页后选中项不变。
4. 长日记右侧出现 `◀ / ▶` 与「第 x/y 页」，翻页可读完整篇，文字不溢出右边界。
5. 换一个女仆再进日记 → 目录内容随之更换（不会残留上一个人的日记）。

## 边界

- 日记内容来自记忆系统的多级索引摘要（**不是**逐条原始记录），与「记忆」页的段落列表是两种视角。
- 单篇正文超过 2000 字会被服务端截断（防止单个包过大）；目录最多 400 篇。
- 日记的生成时机未改动（仍由 `AiMemoryArchiver` 在跨日/周/月边界或玩家睡醒时产出）；
  如果索引为空，日记页自然为空并给出提示。
- 客户端与服务端需同版本（新增了包）。

## 台账

- `rounds/RELEASES.tsv` 两条 1.3.2 行 **第一百一十一次**同号重打，旧字节
  `2df26e6b…a138c0`（forge）/ `34eb8511…b64339`（neo）（第一百一十次 R974）作废。
- `DEV_GUIDE.md` 发布区间 → 八百五十九 ~ 九百七十五。
- 四份发布文案在 R974 两条之后追加本轮条目。
- `机制详解.md`：`AiMemoryStore`（索引存储入口）/ `BlueprintBookNetworking` / `BlueprintBookScreen` 三条补本轮说明。
