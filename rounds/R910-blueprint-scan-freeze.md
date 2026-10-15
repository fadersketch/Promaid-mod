# 实测九百一十 蓝图目录扫描把服务端主线程按死（开手册卡死）

> 跑道：**A 修 bug**（玩家可感知：右键《Promaid 手册》直接把服务器卡死；粉丝在 CurseForge 附 `latest.log` 与三张 spark 快照反馈）。
> 现象原文：「疑似我包里蓝图太多，右键打开那个手册直接游戏卡死了」；快照三次全停在 `BlueprintCatalog.buildCatalogEntriesWithMaterials → BlueprintFileIo.scanExternalBlueprints → loadExternalFile → BlueprintStructureCodec`，第三次停在 `BlueprintMaterials.itemForBlock`。日志反复出现 `scanExternalBlueprints: 新注册 9 个外部蓝图，共 234 个`（20:57–21:00 七八次），单 tick 卡死 166 秒。

## 排班

- **① 定位（已完成，有代码证据）**
  - 调用链：`BlueprintBookItem.m_7203_`（右键、服务端）→ `openFor` → `BlueprintLib.buildCatalogEntriesWithMaterials` → `BlueprintCatalog.buildCatalogEntries` → `BlueprintFileIo.scanExternalBlueprints()` → 逐个 `loadExternalFile()`。**全链同步在服务端主线程**。
  - 扫描本来有增量缓存（每个文件记 mtime，没变就 return），但**登记键 `id = "maid_smart_ext:" + 去扩展名的文件名`（不含目录）**，而 mtime 也是按这个 id 存的（`EXTERNAL_MTIMES`）。于是**两个不同文件只要同名就共用一格 mtime**，互相把对方顶掉 → 每趟都判定"变了" → 重解析。
  - 撞车有两条现成来路：① `.zip` 分支把内层建筑**解压回被扫描的目录本身**（`Files.copy(..., REPLACE_EXISTING)`，`BlueprintFileIo:519-524`），`house.zip` 解出 `house.schem` 与 zip 同名；② 同一个文件名同时存在于 `config/maid_smart/blueprints/` 与 `<存档>/schematics/`（两个扫描根都扫、id 无目录维度）。
  - 材料那半同理：`BlueprintMaterials.countNeedsCached` 拿 `EXTERNAL_MTIMES.get(id)` 当哨兵（`BlueprintMaterials:112`），mtime 一抖就整份重算 → 就是快照里 `itemForBlock` "疯狂拼方块 ID" 那一段（AllTheLeaks 的字符串锁是被牵连的）。
  - **分级**：卡死全服（单 tick 166 s）＝ 严重；触发条件 = 蓝图目录里存在同名文件/zip → 对"导了网络蓝图包"的玩家是必踩。
- **② 修法设计（五条，都在 `BlueprintFileIo` 的扫描/登记层，不动解析器、不加开关）**
  1. **扫描的"变了没"按绝对路径判**：新增 `FILE_MTIMES`（`path.toString() -> mtime`）；`EXTERNAL_MTIMES` 退居为"登记项版本"，只在内容真变了时更新。→ 同名文件不再互相顶 mtime（**根因**）。
  2. **解析结果与已登记内容逐字相同 → 不重登记、不失效四个缓存**。→ 文件被外部 touch / 被自己解压重写，也不再触发材料重算（**根因之二**）。
  3. **同一 id 被多个文件争用 → 先到者胜 + 每 id 只 WARN 一次**。→ 不再来回覆盖（第三根因）。
  4. **登记当场把材料/描述/尺寸预热回来**（`countNeedsCached` / `describe` / `blueprintSizeCached`）。→ 把"开手册时重算材料"挪到扫描这一趟，开手册纯读缓存。
  5. **每趟扫描加墙钟预算**（`SCAN_BUDGET_MS = 40`，每趟至少真解析一个、到点即停）+ **自动续跑**（`SERVER.execute`，不节流、仍按预算）。→ 一次性灌进来的大批蓝图不再"一刀切"在主线程上跑完。
  - **有意不做**：`.zip` 解包仍落在被扫描目录（改落点会牵动"一个 zip 出多张蓝图""删除蓝图按文件名清副本""世界存档 zip 提取"三条路径，需单独一轮实机验证）；解析仍在主线程（惰性化/后台化见新债 TD-20）。
- **③ 落刀清单（每树一份）**：`build/BlueprintFileIo.java`（字段 + `scanExternalBlueprints` 主体 + `setServer` 清理 + `deleteBlueprint` 顺手清 `FILE_MTIMES`）；`DEBT.md` 新增 TD-20 + §0 索引行；本文件。
- **④ 完成判据**：两树 `javac` **EXITCODE=0**；全套护栏 **FAIL 0**；forge↔neo 本文件逐行**仅**映射名差异（无逻辑漂移）；不新增开关、不改玩家可见文案、不改版本号。
- **⑤ 谁验收**：开发者实机——把粉丝那种"234 张 + 几个 zip（含同名 zip）"的目录放好，反复右键手册：①`latest.log` 里 `新注册 N 个` 不再每次出现；②单次打开不再卡死；③手册目录条目、材料表、尺寸与改动前一致。
- **⑥ 卡点**：无。单张超大蓝图/世界存档 zip 的**首次**解析仍在主线程（本轮未后台化，已记 TD-20）。

## 验证
- **编译**：两树 `javac` **EXITCODE=0**（`gen_compile.py` / `gen_compile_neo.py` 重生成 argfile：forge **463** 源 / neo **466** 源；日志里除历史遗留的过时告警外无 error）。
- **护栏**：全套 **FAIL 0** —— `check_comment_style` PASS、`check_comment_dup` 0/0、`check_dev_voice` 0/0、`check_i18n_keys` 0/0、`check_preset` 0/0、`check_panel_coverage` 0/0、`check_reflection` 0/0、`check_catch_swallow` 0/0、`check_workflow` FAIL 0（3 条 WARN 全是版本 / 归档类，见「边界」）、`check_arch` FAIL 0 / **WARN 1**（结构数据陈旧，见「边界」）、`check_tree_mirror` 报告完成（只读，不设门槛）。
- **镜像**：`fc` 逐行比对 `BlueprintFileIo.java` 两树 —— 差异**只有**已知映射名与那两行提到映射名的注释（`net.neoforged.neoforge.items.*`、`FMLPaths` 的 `net.neoforged` 前缀、`SERVER.getServerDirectory()`、`TagParser.parseTag` + `NbtIo.readCompressed(…, NbtAccounter.unlimitedHeap())`），**没有一处新逻辑漂移**（本轮改动的落点两树逐字相同）。
- **三根因逐条对照**
  - ① mtime 判据从 `EXTERNAL_MTIMES.get(id)`（id＝去扩展名文件名，**不含目录**）换成 `FILE_MTIMES.get(p.toString())`（路径唯一）→ 同名文件不再互相顶掉。
  - ② 新增 `prev.equals(steps)` 早退 → 内容逐字没变就不重登记、不清 `NEEDS_*`/`DESCRIBE`/`SIZE` 四个缓存 → 材料不再被无谓重算。
  - ③ 新增 `owner != null && !owner.equals(p)` → 同一 id 撞车**先到者胜**、不再每趟来回覆盖。
- **附加两条**：登记当场 `countNeedsCached` / `describe` / `blueprintSizeCached`（把"开手册时重算材料 / 现算尺寸"挪到扫描这一趟）；每趟扫描 `SCAN_BUDGET_MS = 40` + `SERVER.execute` 续跑，**每趟至少真解析一个**（保证有进展、不会空转），且**早停时跳过错删扫描**（`seen` 不全，清下去会把还没扫到的登记一起误删）。

## 验收
- 状态：**未验收**（等开发者实机）。
- 怎么试：照粉丝的现场备一份蓝图目录（几百张 + 几个 `.zip`，最好含"zip 名与内层文件同名"那种），反复右键《Promaid 手册》：
  ① `latest.log` 里 `scanExternalBlueprints: 新注册 N 个` 不再每次打开都出现（只有真新增/真改动才出现）；
  ② 打开不再卡死（单 tick 不再上百秒）；
  ③ 手册目录条目、材料表、占地尺寸与改动前**一致**（没多没少）。
- 日志：`logs/promaid.log` 搜 `scanExternalBlueprints`（只在有新注册时一行）与 `蓝图 id 撞车`（同名撞车才一行，且每 id 只一次）。

## 边界（没动什么）
- **不加开关、不改任何玩家可见文案、不改版本号**（仍 **1.3.2**）、不动 `store/` 与 `_tmp_pub/`。
- 解析器（`BlueprintStructureCodec` / `BlueprintLib.parseStructure`）、材料口径（`itemForBlock`）、**蓝图 id 命名一律不动** —— 存盘里的 `BuildPlan.blueprintId` 因此仍然认得出老计划。
- **有意不做**（已记 TD-20，未清）：解析仍在服务端主线程（单张超大蓝图 / 世界存档 zip 的**首次**解析仍可能占满一个 tick）；`.zip` 解包仍写回被扫描目录（与用户同名蓝图会覆盖）—— 改落点要牵动"一个 zip 出多张蓝图""删除蓝图按文件名清副本""世界存档 zip 提取"三条路径，需单独一轮实机验证。
- `check_arch` 那条 WARN 是**设计如此**（TD-19 的棘轮把"revision 落后源码"定为 WARN）：本轮改了 `promaid_src*` 但**未提交**（仓库规矩：未声明不 commit），所以结构数据的 revision 仍停在最后一次源码提交。开发者提交后跑一次 `python tools/arch/build_arch.py` 即归零。
- `check_workflow` 的 3 条 WARN（`PUBLISH_GUIDE.md` 停 1.3.1、`promaid-1.2.4.jar` 归档无从核对、更早 13 个 jar 未登记）是本仓库长期存在的归档/发布项，与本轮无关。

## 台账
- 新增行为债 **BD-14**（P1，**已清@实测九百一十**：蓝图一多开手册卡死主线程）与结构债 **TD-20**（未清：解析仍在主线程 / zip 解包仍落被扫描目录），`DEBT.md` §0 索引行同步。
- 未新增开关、未动 i18n / 手册 / 面板文案（本轮无玩家可见面变化）。
