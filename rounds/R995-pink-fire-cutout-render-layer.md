# R995 粉色火焰渲染成黑色方块（cutout 渲染层未登记）

> 上一轮：`rounds/R994-two-block-bridge-filter-elytra-answer.md`（①鞘翅实证答复 ②两格方块硬闸，1.3.3 同号重打，**未发布**）。
> 开发者原话：「1.20.1版本，粉色火焰在下界渲染出黑色方块」（附现场截图：下界红色天空 + 下界岩地面上，
> 一排黑色"黑板"里有粉色火焰图案——正是贴图透明像素被画成纯黑的样子）。

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug（渲染错、要改对；没有新玩法/新开关）。
- **开工前读过**：
  - `rounds/RELEASES.tsv`：1.3.3 两条 `workspace`（R994 重打，未发布）→ 本轮按开发者既有口径**同号重打**
    （**第一百三十一次同号重打**，前一次字节作废）。
  - `ARCHITECTURE-SWITCHES.md`：无"粉火/渲染层"相关开关；本轮**不新增开关**（修错，不是改语义）。
  - `DEBT.md`：BD-26~BD-29 均 `已清`；本条为新发现（见「台账」）。
  - 上一轮记录 R994：1.3.3 未发布、本轮顺延 R995。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位（javap 两张渲染层表 + solid 回退 + FIRE 表项） | 证据链五环每环指着字节码说（见「评估」） | ☑ |
| 1 | 两树改 + 编译 | 客户端专类挂 FMLClientSetupEvent 登记 cutout；两树 `javac` `EXITCODE=0` | ☑ |
| 2 | 14 护栏 | `FAIL 0 / WARN 0` | ☑ |
| 3 | 同号重打 1.3.3 + 装 4 实例 | 双 jar；`match=True`；`RELEASES.tsv` 两条新行（void 旧字节） | ☑ |
| 4 | 交开发者验收 | 实机复看：粉火是粉色火焰形状（透明背景），不再有黑板 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者。
  1. **1.20.1**：下界（或任意维度）让女仆用**床 / 重生锚**炸一次（炸弹开关 `bombing.pinkFire` 默认开）——
     地上的火应是**粉色火焰形状**（火焰之外透明、能看见后面的背景），不再是黑色方板。
  2. **1.21.1**：同场景复看（NeoForge 21.1 的表与回退同构，本轮一并修）。
  3. 对照旧包：同一个存档里本轮的粉火与旧包残留（若还有）观感差异即可分辨。

## 需求 / 来源

- 开发者实机反馈一条（原文见上）+ 现场截图一张（微信传图，随会话附上；本机路径 `C:\Users\Sketch\Desktop\微信图片_20261016182346_5524_3.jpg`）。

## 评估

- **根因：`maid_smart:pink_fire` 没有登记进 cutout 渲染层 → 落回 solid 层 → 贴图透明像素被画成纯黑（"黑板"）。**
  证据链（全部 javap 实证，对象 = 1.20.1 `client-…-srg.jar` + `forge-…-client.jar`）：
  1. **渲染层表按【方块实例】为键**：`ItemBlockRenderTypes.m_276704_`（表初始化）逐条 `map.put(Blocks.X, aload_n)`。
  2. **原版 FIRE 在表里、映射到 cutout**：`Blocks.f_50083_` = FIRE（`Blocks.<clinit>` 里
     `new FireBlock(...)` 后 `putstatic f_50083_` 唯一实证）；它在表中用 `aload_3`，而 `aload_3` 由
     `invokestatic RenderType.m_110463_()` 赋值、`m_110463_` 即 `RenderType.<clinit>` 里字符串
     `"cutout"` 对应的字段 `f_110374_` 的取值口（同法："solid"→`f_110372_`/`m_110451_`，
     `"cutout_mipped"`→`f_110373_`/`m_110457_`）。
  3. **查表未命中回退 solid**：`m_109282_`（查层）字节码 = leaves 特判 → `f_109275_.get(block)` →
     **null 时 `RenderType.m_110451_()`（= solid）**。
  4. **我们的粉火是另一个实例**：`PinkFireBlock extends FireBlock`——表项按实例记忆，**不继承**；
     全树 grep 无任何 `setRenderLayer` / `ItemBlockRenderTypes` 调用（本次修复前的既有面）。
     ⇒ 粉火 = solid 层 = 火焰贴图的透明像素（火焰形状之外）以纯黑写下（截图为证：黑板 + 粉火图案）。
  5. **不是资源问题**：blockstate（10 个模型）与两张 32 帧贴图 + 两条 `.mcmeta` 帧表两树俱在，且
     截图中粉色火焰图案**正常显色**（说明模型/贴图都在被使用，只是层级错）。
- **与维度无关**：渲染层不区分维度；「下界」是现场（她的床/重生锚那一炸在下界最常用）。
- **1.21.1 同病**：NeoForge 21.1 的 `ItemBlockRenderTypes` 同构（javap：`getChunkRenderType` = leaves →
  `renderCutout ? cutoutMipped : solid`，否则 `TYPE_BY_BLOCK.get` → **null 即 `RenderType.solid()`**），
  且其 `render_type` 模型字段**不存在**（扫描四个 jar 的全部 class，无任何类含 `render_type` 字符串）——
  所以 1.21.1 一样必须走 `setRenderLayer`，本轮一并修。
- **同类点**：全模组只注册这一个方块（两树 `BLOCKS.register` 各 1 处 = `pink_fire`），登记一条即全覆盖；
  其余自定义"方块形"内容都是渲染器/实体，不经此表。

## 改法

- **两树客户端专类** `PromaidClientSetup` 各新增 `registerPinkFireRenderType(...)`：
  挂 **FMLClientSetupEvent**（`enqueueWork`）→ `ItemBlockRenderTypes.setRenderLayer(PinkFireBlock.block(),
  cutout 渲染类型)`。Forge 树用 SRG `RenderType.m_110463_()`（= cutout，javap 实证；行内注释标注口径），
  Neo 树用 `RenderType.cutout()`。
- **时机硬校验（javap 实证）**：`setRenderLayer` 内部 `checkClientLoading =
  Preconditions.checkState(ClientModLoader.isLoading(), "Render layers can only be set during client
  loading! This might ideally be done from `FMLClientSetupEvent`.")`——**只允许客户端加载期**调用，
  故必须挂事件口；这也是"为什么以前没人发现/随手补不上"的答案。
- **调用点**：两树 `ProMaidMod` 的 `dist.isClient()` 分支各加一行静态调用（客户端类型不外泄主类，
  沿用工程既定纪律与 RuntimeDistCleaner 教训注释）。
- **被否方案**：
  （a）把 `"render_type": "minecraft:cutout"` 写进 10 个模型 JSON——扫四个 jar 全部 class 无
  `render_type` 字符串，NeoForge 21.1 与 Forge 1.20.1 都不支援该字段，此路不通；
  （b）在 mod 构造器里直接调 `setRenderLayer`（构造期 `isLoading` 为真也能过校验）——不如官方预留
  事件口稳妥，且构造器分支里多一行裸调用没有收益；
  （c）改 `Blocks.FIRE` 自己的表项——表按实例，改的是原版火，救不了我们的实例；
  （d）让粉火"长得像原版"而不再用自定义方块——推翻整个"边点边换、只碰她那一炸"的设计（v1.2.2 实测五百九十七），
  且原版火无法满足"粉"。

## 触及文件

- 两树各 2 个：`client/PromaidClientSetup.java`、`ProMaidMod.java`。
- 新配置项 0；新 i18n key 0；新类 0；新 mixin 0。

## 验证

- 命令 + 结果：
  - 两树 `javac` **EXITCODE=0**（`gen_compile.py`+`compile_promaid.bat` / `gen_compile_neo.py`+`compile_neo.bat`）。
  - 14 护栏**全 FAIL 0 / WARN 0**（改后一轮 + `RELEASES.tsv`/`DEBT.md` 登记后复跑一轮，均全绿；`check_comment_style` PASS）。
  - 双 jar（**同号重打**）：`patched/promaid-1.3.3-forge-1.20.1.jar` **17915821** 字节，sha256 `0cb6ebb3…eaf0e8`；
    `patched/promaid-1.3.3-neoforge-1.21.1.jar` **17963425** 字节，sha256 `9f0ac999…042c02`；桌面快照自动同步
    （forge=0cb6ebb3c1d334f3 / neo=9f0ac9997ae5eb5a）。
  - 部署 4 实例 **match=True**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险 v2.4a / server pack1201；
    旧 R994 字节自动备份进 `patched/backup_old/`）。
  - 专服回归三件 **PASS**（`test_server.py 1201`：`Done (3.167s)`；`test_server.py neoforge1211`：
    `Done (1.287s)`；`test_maid_load.py neoforge1211`：`verdict: PASS`）。
- **反证**（修 bug 必填）：
  - 旧代码即反证：粉火不在层表内（表按实例）、全树 `setRenderLayer` 调用数 = 0 → `m_109282_` 的 null
    分支回退 `RenderType.m_110451_()` = **solid**（字节码实证）；修复后同一路径命中 cutout。
  - 现象反证：截图里**粉色火焰图案正常显色**（贴图/模型/帧表都在被使用——不是缺资源/缺模型），
    错的是层级（透明像素在 solid 层变纯黑）。
  - 1.21.1 同构反证：Neo 的 `getChunkRenderType` 未命中同样回退 `RenderType.solid()`（字节码实证）→
    一并修，不是只修 1.20.1。
- 未做：实机跑一遍（装机后由开发者验，见「验收」）——专服回归三件已按惯例跑完 PASS。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）

- 状态：`未验收`（等开发者按「排班」两条场景实机复看）。
- 未验收之前：不写 changelog、不写 release 说明、不发平台。

## 边界（没动什么）

- `PinkFireBlock` 本身（生成/熄灭/免伤/AABB）与 `BaseFireBlockPinkMixin`、`PinkFireSweep`：一字未动（只是给它换了渲染层）。
- 方块模型/贴图/`.mcmeta`：一字未动。
- 原版火（`Blocks.FIRE`）与其他模组火：不碰（表项按实例，我们只登记自己的）。
- 版本号仍 **1.3.3**（未发布时段同号重打，旧字节在 `RELEASES.tsv` 记废）。

## 台账

- 清掉：无既有债号。本条属**新发现**，已登记 `DEBT.md` BD-30（粉火 cutout 渲染层未登记，战斗组），
  状态 `已清@实测一千〇二`；§0 模块索引「战斗 / 骑乘 / 载具 / 蹲下语义」行已补 BD-30 与收项描述。
- 发布登记：`rounds/RELEASES.tsv` 追加 **1.3.3** 两条 `workspace`（forge `0cb6ebb3…eaf0e8` /
  neoforge `9f0ac999…042c02`，2026-10-16，**第一百三十一次同号重打**：本行现字节；
  前一次 `8daa47ab…114970` / `6c83b55b…f6bc3e` 作废）。
- 升号：无（1.3.3 未发布 → 同号重打，§5.5 清单不动）。
- 新增债：见上（BD-30 本轮即清）。
