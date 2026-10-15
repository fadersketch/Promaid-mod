# R965 导入蓝图：优先弹系统文件选择框（环境不支持时自动退回游戏内浏览器）

## 排班
- **跑道**：B 新功能（开发者指令）——一句话判定依据：把「选文件」这一交互换成系统对话框（多一条能力、不改判定与数据）；属"交互/表现增强"而非修错。
- **开工前置**：读过 `WORKFLOW.md` §0~§4、`DEBT.md`、`rounds/RELEASES.tsv` 1.3.2 两行（第一百次 R964）、上一轮 `rounds/R964-*.md`、`gui/FilePickScreen.java`（现选择器本体与类注释里的历史原因）、`build/BlueprintBookScreen.java`（两个导入按钮 + 两个取文件函数）、设置面板「导入语音包」的 AWT `FileDialog` 实现（v1.5.250，作为样板），并**检索四个游戏实例的版本 JSON / 启动参数**确认 `headless` 零命中。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位"导入蓝图"的现路径 | 手册右上角两个按钮 → 版本确认 → `FilePickScreen`（游戏内分页浏览器）→ 发包给服务端导入 | ☑ |
| 1 | 找可复用的系统框样板 | 设置面板「导入语音包」= `java.awt.FileDialog` + daemon 线程 + 回 MC 主线程回调（v1.5.250） | ☑ |
| 2 | 定"为什么不直接换回去" | 类注释写明 v1.0.4 从 AWT 换出的原因 = headless 启动器抛 `HeadlessException` → 本轮必须**探测 + 退回**而不是替换 | ☑ |
| 3 | 核实运行环境是否 headless | 检索四个实例的版本 JSON 与启动参数：`headless` 零命中 → 系统框能弹 | ☑ |
| 4 | 两树加 `FilePickScreen.openPreferred` | headless→ `open()`；否则 daemon 线程弹系统框；抛 Throwable → 留痕并退回 `open()`；取消不算失败 | ☑ |
| 5 | 两树两个导入函数接上 | `pickAndImportBuild` / `pickAndImportWorld` 改调 `openPreferred`；两处注释同步改写 | ☑ |
| 6 | 两树编译 / 护栏 / 打包 / 部署 / 回归 | `EXITCODE=0` ×2；14 支 `FAIL 0`；4/4 `match=True`；四件回归全 PASS | ☑ |
| 7 | 交开发者验收 | 开发者实测后给出结论 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = 打开手册点「⬆ 导入建筑」（先过版本提示）看是否弹出**系统**打开文件窗口、能否翻到深层目录选到 `.schem/.litematic/.nbt/.zip`、选完是否回聊天框出结果；再点一次但**点取消**，确认安静返回手册（不弹游戏内浏览器）；「⬆ 导入地图」同样走一遍。

## 需求 / 来源
- 开发者：「能不能优化下导入蓝图？比如跳出弹窗让玩家自己在文件夹中选。」

## 评估
- **现状**：手册右上角「⬆ 导入建筑」/「⬆ 导入地图」→（前者先过版本确认弹窗）→ **游戏内文件浏览器 `FilePickScreen`**：从磁盘分区逐级进目录，12 行/页、翻页；点文件即回调绝对路径 → 发包给服务端导入到 `config/maid_smart/blueprints/`。
- **要换成什么**：同一份"选文件"需求，设置面板「导入语音包」走的是一条更顺的路——**`java.awt.FileDialog`**（v1.5.250 落地：独立 daemon 线程开框、选完切回 MC 主线程发包）。系统框自带目录树 / 地址栏 / 文件名输入，找深层目录里的图纸比游戏内分页列表快得多。本轮就是把蓝图导入接到同一条路上。
- **为什么不直接换回去**：`FilePickScreen` 的类注释写着 v1.0.4 是从 `java.awt.FileDialog` 换过来的，原因是**部分启动器以 `-Djava.awt.headless=true` 跑游戏 → AWT 抛 `HeadlessException`，选择器打不开**。所以本轮的设计是**先探测再决定**：
  - **非 headless**（已实证开发者的四个实例都是：各版本 JSON 与启动参数检索 `headless` 零命中）→ 弹系统框；
  - **headless / 弹框抛任何 Throwable** → 立刻退回原来的游戏内浏览器（v1.0.4 那套修复原样保留，一行未改）；
  - **取消 ≠ 失败**：系统框里点取消只是取消，**不会**再弹一次游戏内浏览器（只有"根本弹不出来"才退回）。
- **两处按钮都换**：导入建筑 / 导入地图 是同一条"选文件"路，不留半截。
- **文件位置语义不变**：路径是**服务端所在机器**的路径（单机 = 自己电脑；专服需文件在服务器上再选）——这是原口径，本轮未动、也未放宽。
- **级别**：**P3**（交互增强；无判定 / 数据影响）。
- **风险**：低-中。AWT 对话框在个别平台有行为差异——Windows 的 `FileDialog` 对 `FilenameFilter` 支持有限（可能列出全部文件，而不是只列蓝图格式），最坏结果是玩家选错文件、由**服务端原有校验**回一条明确原因（"不支持的格式"）。护栏三道：headless 探测、Throwable 退回、服务端格式/同名/路径穿越校验（均未改动）。

## 改法
1. **两树 `gui/FilePickScreen`**：新增静态 `openPreferred(parent, title, exts, onPick)`——
   - `java.awt.GraphicsEnvironment.isHeadless()` 为真（或探测本身抛异常）→ 直接调既有 `open(...)`（游戏内浏览器）；
   - 否则起 daemon 线程开 `java.awt.FileDialog`（`new FileDialog((Frame) null, title, LOAD)`，`setFilenameFilter` 按传入的扩展名过滤），`setVisible(true)` 后取 `getDirectory()+getFile()` → `Minecraft.execute(...)` 回主线程回调；
   - 抛任何 `Throwable` → `Diag.swallow("FilePickScreen", e)` 留痕 + 回主线程 `open(...)`；`finally` 里 `dispose()`；线程起不来同样退回。
2. **两树 `build/BlueprintBookScreen`**：`pickAndImportBuild` / `pickAndImportWorld` 的 `FilePickScreen.open(...)` → `openPreferred(...)`；两处注释从"为什么改用游戏内浏览器"改写为"优先系统框、headless 退回"。

## 触及文件
- 两树各 2 个：`gui/FilePickScreen.java`（+1 个静态方法）、`build/BlueprintBookScreen.java`（2 处调用 + 2 处注释）。
- 新配置项 / 新 i18n key / 新 mixin / 新类：**0 / 0 / 0 / 0**。
- 生成物：`panel_coverage.txt` 与 `ARCHITECTURE-SWITCHES.md` 本轮不涉及（无配置/面板改动）；14 支护栏重跑确认无漂移。
- 不动：设置面板语音包那条 AWT 路径（它是本轮样板，工作正常，保持原样）、服务端导入实现（`BlueprintFileIo.importBuildFile` / `importWorldFile` 的全部校验）、游戏内浏览器本体（从"唯一选择器"变成"退回目标"，代码零改动）、两个导入网络包与协议。

## 验证
- **编译**：两树 `javac EXITCODE=0`。
- **护栏**：14 支全 `FAIL 0`（含既有 §8 配额 WARN，见「台账」）。
- **镜像**：两树逐字对应（仅 `Minecraft.m_91087_()` / `m_18707_()` ↔ `Minecraft.getInstance()` / `execute()`）；`check_tree_mirror` 无新增差异项。
- **打包**：forge `17635799` / neo `17682814` 字节；部署 4/4 `match=True`（两个 D 盘目标走提权复制，`elevated rc: 0`）；新 sha256 forge `d53b9ff8…a72eb4` / neo `eb400fb7…dbc93a`（台账两行已改 sha 列并追加「第一百零一次同号重打」说明）。
- **回归**：四件全 PASS（`test_server` / `test_maid_load` × 1201 / neoforge1211）。
- **反证**（改前 / 改后）：改动只在"打开哪一个选择器"这一层——`onPick` 回调里发的两个包（`BuildImportPacket` / `WorldImportPacket`）与参数一字未改；服务端 `BlueprintFileIo` 两个导入函数**零改动**；`FilePickScreen` 的游戏内浏览逻辑（`reload` / 目录进入 / 分页）**零改动**；headless 探测分支与"取消"分支都不发包、不落盘。
- **未做**：**没有实机点按钮**（本环境没有游戏）——"系统框长相/能不能选到/取消是否安静"留给开发者实测；headless 退回分支同理只有代码级证明。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）
- 状态：**未验收**。已部署@2026-10-14、已回归@2026-10-14（四件全 PASS）。
- 未验收之前：不写 changelog、不发平台（只**追加**到同一份未发布的 1.3.2 文案）。

## 边界（没动什么）
- **不动"导入到哪"**：仍是服务端 `config/maid_smart/blueprints/`，仍走服务端解析 → 登记 → 刷新目录。
- **不动校验**：不支持的格式 / 同名已存在 / 路径含 `..` / 世界包缺 `level.dat` —— 服务端那些报错一字未改。
- **不动版本确认弹窗**：点「导入建筑」先弹的版本提示仍是第一道门。
- **不动语音包那条**：它本来就是 AWT 样板，保持原样（将来若要统一到 `openPreferred`，等指令）。
- **不动游戏内浏览器**：它从"唯一选择器"变成"退回目标"，代码零改动。
- **不动文件位置语义**：仍是服务端机器上的路径（单机 = 自己电脑）。
- **不动版本号**：仍 1.3.2。

## 台账
- 清掉的债：无（本轮是开发者当场点名的交互增强）。
- 新增的债：无。
- 配额：**功能轮带一条 WARN**（同 R963 / R964 的处置）——`check_workflow` §8 口径"最新轮次是功能轮时不得有未清的 P0/P1 债"，而 **BD-04(P1) 仍 `未清`**。为什么不在这轮清它：本轮是开发者当场点的小功能，按他「后续的工程不要做，等我指挥」的长期口径，不擅自把别的债塞进来。`FAIL 0` 照旧满足（该项只有 WARN）；同 R906 / R907 / R960 / R961 / R963 / R964 的处理，如实留痕。
