# R972 记忆可视化：手册「记忆图谱」页（重要度分布 / 记忆时间轴 / 关系网络）

## 排班
- **跑道**：A 新功能轮（开发者点名做）——玩家反馈「有一部分玩家希望女仆的记忆是可视化的」。本轮把记忆从"一列文字"变成"三张图"，只读、不改记忆内容与数值。
- **开工前置**：承接 `rounds/R971-*.md`（记忆可读性）；读 `build/BlueprintBookScreen.java`（记忆页 tab / 渲染 / 按钮三处）、`build/BlueprintBookEntityPackets.java`（包定义两树写法不同：forge `SimpleChannel` + `NetworkEvent.Context`，neo `PayloadRegistrar` + `IPayloadContext` + `TYPE` + `type()`）、`build/BlueprintBookNetworking.java`（`collectMemoryLines` / `collectDebugStatus` 的取数口径）、`memory/AiMemoryModels.java`（Paragraph 的 salience/tags/permanent、Relation 的 confidence/inactive）、`memory/AiMemoryIndexStore.java`（IndexRecord 的 level/startDay/endDay/content）、`memory/AiMemoryGraph.java`（已有的关系多跳召回——本轮复用它的"实体=关系的 subject/object"口径，但**不**调用它）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 数据从哪来 | 图谱要的是"结构"不是原文：段落重要度直方图、日记的日区间与级别、关系的两端与置信度、实体的度数——全部能从 `AiMemoryStore` 现成取到，**不必改存储格式** | ☑ |
| 1 | 服务端采集器 | `BlueprintBookNetworking.collectMemoryGraph(maid, level)`：产出 `STAT/HIST/TIME/EDGE/NODE` 五类结构化行（坏行由客户端跳过），原文一律不下发 | ☑ |
| 2 | 新包对（两树） | `MemoryGraphRequestPacket`（C2S，权限同链路调试：主人或 OP）+ `MemoryGraphResponsePacket`（S2C，行列表）；forge 用 `registerMessage(27/28)`，neo 用 `playToServer/playToClient` | ☑ |
| 3 | 客户端第三页 | 女仆记忆页加 tab「图谱」；页内三张图切换：**重要度分布**（0~10 档柱状，柱高=条数）／**记忆时间轴**（横轴=游戏日，四条泳道=日/3日/周/月，色条=一篇日记的覆盖区间）／**关系网络**（实体排圆上、连线=关系、亮度∝置信度，12 实体一页） | ☑ |
| 4 | 空态与边界 | 没段落/没日记/没关系各自给一句人话提示；窗口太矮宁可不画也不画到屏幕外；换女仆/退出该页 `clearGraph()` 防串数据 | ☑ |
| 5 | 两树镜像 + 编译 | 两树同改；`EXITCODE=0` ×2 | ☑ |
| 6 | 护栏 / 打包 / 部署 / 回归 | 14 支 `FAIL 0`；双 jar；4/4 `match=True`；四件回归全 `verdict: PASS` | ☑ |
| 7 | 台账 / 文案 / 收尾 | `RELEASES.tsv` 第一百零八次；`DEV_GUIDE.md` → 九百七十二；发布文案补一条；`机制详解.md` 同口径 | ☑ |
| 8 | 交开发者验收 | 开发者实测后给出结论 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = ① 手册 → 女仆记忆 → 点一只女仆 → 顶部第三个 tab「图谱」；② **重要度分布**：横轴 0~10、柱高 = 该档段落条数（和她聊得越多、越重要的事柱子越高）；③ **记忆时间轴**：横轴 D 数字 = 游戏日，四条泳道分别是 日/3日/周/月 日记，色条越长 = 该篇覆盖天数越多（睡一觉后应能看到新色条）；④ **关系网络**：圆点是实体、连线是关系（越亮越可信），实体多于 12 个时底部出现 ◀/▶ 翻页；⑤ 新女仆（没记忆）三个页面都应显示"还没有…"的提示而不是空白或报错。

## 需求 / 来源
- 开发者：「做一下」（承接 R971 交付说明里提出的"真正的记忆可视化：时间轴 / 关系网 / 重要度分布"）。
- 玩家原话（R971 转述）：「有一部分玩家希望女仆的记忆是可视化的」

## 设计
1. **只传结构，不传原文**：图谱行只有数字与短实体名（`graphField` 会把 `|` 换成 `/`、去掉 `§` 颜色码），记忆正文一条都不额外下发——"看内容"仍走记忆页/链路调试的既有通道。四类上限：日记 400 条、关系 240 条、实体 240 个、关系网单页 40 条连线（避免超大记忆把包和帧都撑爆）。
2. **五类行格式**（客户端按首段关键字分发，逐行 try/catch，坏行静默跳过）：
   - `STAT|段落|有效关系|日记|永久|长期|最小日|最大日`
   - `HIST|重要度0~10|条数`
   - `TIME|起日|止日|级别码|字数`（级别码 0 日 / 1 三日 / 2 周 / 3 月）
   - `EDGE|主体|谓词|客体|置信度`
   - `NODE|实体|度数`
3. **三张图各自的画法**：
   - 重要度分布：11 根柱（不可用 `fillGradient` 渐变，直接纯色填充），颜色沿用记忆列表的"重要度配色"（≥8 金 / ≥5 绿 / 其余灰）；柱顶标条数、柱底标档位，空档画一条底线（不画成"零高柱"）。
   - 时间轴：横轴按 `最小日~最大日` 线性映射到内容区宽度，日刻度最多 8 条；每条日记在自己泳道里按 `索引 % 每道可叠层数` 叠放（同一天多篇不会互相盖死）。
   - 关系网络：实体按 `-90°` 起均匀排在一个圆上（半径随窗口自适应），连线用"2px 步长的 1px 方块"拼出（`GuiGraphics` 没有画线接口）；节点度数按 `NODE` 行给出（度数=它出现过的关系条数）。
4. **交互**：三张图用底部一排按钮切换（`h-46`）；关系网一页 12 个实体、超过才显示 ◀/▶（`h-26`，与记忆页翻页同一处位置）；`graphMode`/`graphPage` 都随切图/切女仆归零。

## 触及文件
- 两树 × `com/maidsmart/build/BlueprintBookScreen.java`（图谱字段 + `showMemoryGraph`/`clearGraph`/`requestMemoryGraph` + 第三个 tab + `memTab==2` 的按钮/渲染分发 + `graphViewButtons`/`renderGraphView`/`drawSalienceHist`/`drawTimeline`/`drawRelationNet`/`drawThinLine`）。
- 两树 × `com/maidsmart/build/BlueprintBookEntityPackets.java`（`MemoryGraphRequestPacket` / `MemoryGraphResponsePacket`）。
- 两树 × `com/maidsmart/build/BlueprintBookNetworking.java`（注册 27/28；`collectMemoryGraph` + `bumpDegree`/`graphField`/`graphLevelCode`/`sendGraphResponse`）。
- 台账 / 文档：`rounds/R972-*.md`（本文件）、`rounds/RELEASES.tsv`（第一百零八次同号重打）、`DEV_GUIDE.md`（区间 → 九百七十二）、`机制详解.md`（两条补记）、4 份发布文案（加一条）。
- **未动**：记忆的存储格式与内容、情绪/亲密数值、链路调试页、完工重放、任何开关默认值、`AiMemoryGraph`（关系多跳召回仍归检索链路，图谱页只是把它依赖的"实体"口径复用了一遍）。

## 验证
- **两树编译**：`javac EXITCODE=0`（两树；neo 侧 C2S/S2C 走 `PacketDistributor.sendToServer/sendToPlayer`，forge 侧走 `CHANNEL.sendToServer` / `PacketDistributor.PLAYER`）。
- **护栏 14 个**：全 `FAIL 0`（`check_arch`/`check_comment_style` 均未新增未覆盖模块或超长注释块）。
- **打包**：forge **17650598** 字节（sha256 `bbc82602…e55d95`）；neoforge **17697984** 字节（`f120f51a…4f2458`）。
- **装机**：4 个本机实例全部 `match=True`（`elevated rc: 0`）。
- **回归**：四件全 `verdict: PASS`（forge 专服启动 / forge 女仆加载 / neo 专服启动 / neo 女仆加载）。
- **留痕**：`RELEASES.tsv` 两条 1.3.2 行 **第一百零八次**同号重打；R971 旧字节 `e874e6a8…1e5b28`（forge）/ `f32a0f51…c18d47`（neo）作废。

## 验收
- 状态：**未验收**（已部署；未 commit、未 push）。
- **判据**：见"谁验收 / 怎么验"五条；核心是"三张图能看、能看懂、换女仆不串数据"。

## 边界
- **图谱是只读视图**：不给任何"点图改记忆"的入口（增删/加权仍走链路调试页的既有动作按钮）。
- **不传原文**：图谱页看不到记忆正文（那是记忆页与"选中行全文"的事），它回答的是"她记了多少、记的多重、什么时候记的、记得谁"。
- **时间轴用的是游戏日**（日记的 `startDay~endDay`），不是现实时间；跨天整理由 `AiMemoryArchiver` 触发（睡醒或跨日边界），所以刚聊完不会立刻出现新色条。
- **关系网的"实体"取自关系的两端**（与检索链路的多跳召回同一口径）：只出现在段落里、没进任何三元组的名字不会上环。
- **一页 12 个实体是刻意的**：再多人名会挤成一团；要看得更多用 ◀/▶ 翻页（总数由 `NODE` 行给出）。
- **不新增配置开关**：图谱页随手册打开即可用，无服务端/客户端开关。

## 台账
- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第一百零八次同号重打。
- `DEBT.md`：不新增条目。
- `DEV_GUIDE.md`：发布区间 `八百五十九 ~ 九百七十二`。
