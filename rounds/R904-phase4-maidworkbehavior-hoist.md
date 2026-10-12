# 实测九百〇四 ④ 抽 `MaidWorkBehavior` 基类（安全子集落刀）

> 跑道：重构 / 工具（不改玩法语义）。承接 `R903`；对应开发者「随后逐步推进 4，5，6」的第一刀。

## 排班
- ① `R902` 配置文案去叙事（已完成）；② `R903` 架构测绘工具 + 流程规范化（已完成）；③ **本轮**：④ 第一刀——只上提两文件**逐字相同**的成员，编译可验、行为透明；④ 的完整驱动逻辑合并、⑤、⑥ 第二步仍等开发者「部署」开闸。

## 需求 / 来源
- 开发者：「先存档，后动手」——存档（checkpoint commit）已完成，随后开始 ④⑤⑥ 的动手。
- 目标（`DEBT.md` §6 ④）：`task/MaidMineBehavior` 与 `task/MaidWoodBehavior` 为系统性克隆，抽公共基类去重。

## 做了什么
- **新增 `task/MaidWorkBehavior.java`（两树各一份）**：`abstract class MaidWorkBehavior extends Behavior<EntityMaid>`，构造沿用子类原写法 `super(java.util.Collections.emptyMap(), Integer.MAX_VALUE, Integer.MAX_VALUE)`（无限时长，逐字一致 → 行为完全透明）。
- **上提 7 个「逐字相同」成员**（每文件约 199 行）：静态数据表 `JUNK_STONES`、`OPEN_BREAKABLE`(+`static` 块)、`BLOCK_CN`(+`static` 块)，纯工具 `distSqToBlock`、`blockCnName`、`isSoft`、`canHarvest`；可见性 `private static` → `protected static`。带前导 javadoc 一并上移，避免子类留孤儿注释。
- **两个子类**：`extends Behavior<EntityMaid>` → `extends MaidWorkBehavior`；`super(emptyMap, MAX, MAX)` → `super()`；删除被上提的成员及其 javadoc。
- **两树分别落地**（映射名不同，不跨树共享源码）：Neo 侧基类去掉 `net.minecraftforge.registries.ForgeRegistries` import——Neo 的采集逻辑注册表API 一律全限定 `net.minecraft.core.registries.BuiltInRegistries`。
- **`check_tree_mirror.py` 复测**：同路径 `.java` 459 → **460**（+基类）；把数字回填 `DEBT.md` §6.1。
- **`DEBT.md` §6.1 ④ 段**：改写为「已落刀的安全子集 + 剩余待办」，并纠正蓝本里的一处误判——`reachSq` **不可上提**（挖矿读 `MINE_REACH`、伐木读 `WOOD_REACH`，同名不同源）。

## 验证
- 两树编译：`tools\build\compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**（先 `python tools\build\gen_compile*.py` 重生成 argfile，使新基类进入源清单：forge 462 / neo 465 sources）。
- 护栏全套 `FAIL 0`：`check_comment_style` PASS（927 文件 0 处问题）、`check_catch_swallow` / `check_comment_dup` / `check_dev_voice` / `check_i18n_keys` / `check_panel_coverage` / `check_preset` / `check_reflection` / `check_workflow`（FAIL 0 / WARN 3，3 条 WARN 均为既有发布登记项，与本轮无关）。
- **镜像保真**：新基类 forge↔neo 的 diff（27 行）**全部**是 SRG↔Mojmap / 注册表 API 替换——`m_123341_()`↔`getX()`、`m_20185_()`↔`getX()`（实体）、`m_135815_()`↔`getPath()`、`m_41619_()`↔`isEmpty()`、`m_41720_()`↔`getItem()`、`m_8096_()`↔`isCorrectToolForDrops(...)`、`m_21205_()`↔`getMainHandItem()`、`ForgeRegistries.BLOCKS`↔`BuiltInRegistries.BLOCK`；无一处逻辑差异。
- 未部署、未跑实机挖矿/伐木回归（本轮只做编译可验的安全子集）。

## 验收
- 状态：`未验收`（编译 + 护栏 + 镜像可自证；完整 ④ 合并与运行时行为需开发者「部署」开闸回环测试）。
- 行为透明性依据：基类仅承接静态常量与纯函数，构造参数与原子类逐字相同，未改任何调用点语义。

## 边界（没动什么）
- 未合并**驱动逻辑**（扫描-接近-连锁-回收）：两树对应方法行数/写法均有差异（非逐字克隆，含任务特异分支），需逐方法人工核对语义，属下一步，**本轮未动**。
- 未删除已识别的死代码（`MaidMineBehavior` 的 `destroyMarked` 与一处 `v1.2.3-dbg` 探针）——非本次授权范围。
- 未动 ⑤（`PromaidConfigScreen`）、⑥ 第二步（共享源目录）；未动 `patched/`、`store/`、`_tmp_pub/`；未改版本号（仍 1.3.2）；未 commit / push。
- 临时脚本（`_hoist.py` / `_mirror.py` / `_gates.py` 等）用后即删。

## 台账
- ④ 安全子集完成（本轮）；④ 完整合并、⑤、⑥ 第二步待办（`DEBT.md` §6.1 蓝本已更新）。
