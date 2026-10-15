# 实测九百四十六 压缩盒差异化补全：重生锚也一次多放（复用黑曜石/水晶那套落点）

> 跑道：承接 `rounds/R945-compression-box-burst.md`（那一轮把「重生锚多放」记成待办，理由是"另一套落点几何"）。

## 排班

- 单点补全：把 R945 的「盒子供弹 → 一次多放」从 TNT / 末地水晶扩到**重生锚**。
- 步骤：读落点函数（`placeOnSupport` / `tryPlaceAll` / `removePlaced`）与相位机 → 改 Forge →
  镜像 Neo（逐锚点断言唯一；SRG↔Mojang 名按树替换）→ 两树 `javac EXITCODE=0`（中途一次编译失败并修掉）→
  全护栏 `FAIL 0` → 打 jar → 部署 4 实例 `match=True` → 台账/轮次记录 → 清临时脚本。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

开发者原话：

> 「重生锚和黑耀石都能做到同时放置，那这边的代码直接套用不就好了。」

**这是对我上一轮判断的纠正，且纠正得对**：重生锚和黑曜石底座都是**普通整块方块**，
`tryPlaceAll` 的邻格 ring 对它们一视同仁——R945 里我把「多放重生锚」当成需要专门落点几何的另一件事，
是想复杂了。（会需要专门处理的其实是**床**：原版床占两格，`stepBlock` 里那条 `spot + spot.offset(facing)`
就是为它写的双格记录。）

## 定位与改法（本轮落地项）

- **`BombItems`**：加 `boxBurstAnchor(maid)`（`boxBurst(maid, s -> isStack(s, ID_RESPAWN_ANCHOR))`），
  与 `boxBurstTnt` / `boxBurstCrystal` 同一套 `BOX_BURST = 5` 口径。
- **`BombPlacement.stepBlock`**：
  - 方法开头先算 `anchorBurst = BombItems.boxBurstAnchor(maid)`——**必须在取第一枚重生锚之前**算，
    否则首枚从盒子出库会把盒子计数减 1，能放的个数少一个（与 R945 水晶那处的同一考量）。
  - 放下第一枚并记进 `ph.placed` 之后，若 `anchorBurst > 1`：构造邻格 ring
    （四邻 + 四斜 + 正方向 2 格，共 12 格），`sortByDistToMaid` 排近的先放；
    逐枚 `takeOne(ID_RESPAWN_ANCHOR)` → `tryPlaceAll(..., requireSupport=true)` → 记进 `ph.placed/placedBlock`。
    - **复用而非新造**：落点、支撑面要求、原版拒绝后的强制兜底、`claimedByOtherPhase`（防与已放那格重叠）
      全部走既有 `tryPlaceAll`——「直接套用」就是这么落地的。
    - 放不下 / 取不到 → 停止并还她原物（`giveBack`），已放的照常走完。
- **`BombPlacement.stepPayload` 的 ANCHOR 分支**：由「对 `base` 充一次能」改为
  「对 `ph.placed` 里**每个**锚各充一次能」（各消耗 1 颗萤石，`cfgAnchorNeedsGlowstone` 关掉时都不消耗）。
  萤石中途不够 → 记一条日志、把**已充上**的那几个照常起爆（`anchorCharged` 记录个数）；一颗都没有 → 放弃（沿用旧口径）。
- **多炸登记**：`anchorCharged > 1` 时，为第 2..N 个锚各登记一个 `Bomb`（`pos` 各取 `ph.placed.get(i)`，**同时起爆**），
  与首炸**共用同一份 placed 列表**——`removePlaced` 里那句 `if (st.getBlock() != want) continue;`
  让「拆过一次的格子」自动跳过，所以不会重复撤、不会重复还（重生锚那一炸本就由原版 `use()` 先 removeBlock 再 explode）。
- **面板**：压缩盒页那条只读说明补上重生锚，并把消耗口径写清（每发 TNT 一份点火料 / 每个锚 1 颗萤石 / 每枚水晶 1 件）。

## 触及文件

- 两树 × `com/maidsmart/combat/BombItems.java`（`boxBurstAnchor`）
- 两树 × `com/maidsmart/combat/BombPlacement.java`（stepBlock 并排多放 + stepPayload 逐锚充能 + 多炸登记）
- 两树 × `com/maidsmart/config/PromaidConfigScreen.java`（压缩盒页说明补重生锚）
- `rounds/RELEASES.tsv`（第八十五次同号重打）+ `rounds/R946-compression-box-anchor.md`（本文件）
- **未动**：`MaidSmartConfig`（未新增配置项）、床位多放（见下）、盒子本身、爆炸威力与回收口径。

## 验证

- **两树编译**：`compile_promaid.bat` / `compile_neo.bat` → `EXITCODE=0`。
  **中途一次失败**：多炸登记块先行引用了尚未声明的 `posList` / `blockList`
  （它被我插在水晶块之前、而 `posList` 声明在其后）→ 报「找不到符号 · 位置: 类 BombPlacement」
  两处。改为就地 `new ArrayList<>(ph.placed)` / `new ArrayList<>(ph.placedBlock)` 后通过。
- **全护栏**：11 个全 `FAIL 0`（`check_comment_style` PASS、`check_panel_coverage` FAIL 0/WARN 0、
  `check_preset` 173/177 FAIL 0、`check_i18n_keys` FAIL 0、`check_arch` / `check_comment_dup` /
  `check_dev_voice` / `check_reflection` / `check_catch_swallow` FAIL 0；`check_workflow` FAIL 0 + 3 条历史 WARN）。
- **两个 jar**：forge **17596706** 字节（sha256 `2934B262…2A122F`）；
  neoforge **17632957** 字节（`EE65810E…1DE168`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 行更新 sha256 并列第八十五次同号重打；
  九百四十五 的旧字节 `71CFD2FE…226CD7`（forge）/ `72F3870D…F21544`（neo）作废。
- **未跑实机回归**（已部署，等开发者实测）——需实测确认：并排 5 个重生锚的落点是否都找到支撑、
  5 炸的伤害/性能、萤石消耗体感（5 颗）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- **判据**：
  - 盒子里放 ≥2 个重生锚（且开「女仆背包延伸」），女仆在下界/末地那种"锚会炸"的维度里
    一次并排放 5 个重生锚、同时起爆。
  - 萤石只有 3 颗时 → 只充上 3 个并照常起爆（日志写明"萤石不够，只充了 3 个"）。
  - 地面不平、邻格没有支撑 → 能放几个放几个，剩下的原物还她。
  - 盒子空 / 没开背包延伸 → 回到原来的「一次 1 个」。
  - 起爆后那几块重生锚不会重复回收（它们本就被自己那一炸消耗，且我们只兜底撤一次）。

## 边界（没动什么）

- **没动**：TNT 多投与水晶多放的既有实现；爆炸威力（`Kind.ANCHOR = 5.0F` / 每枚一炸）；
  回收与回滚口径；维度闸（重生锚只在"这个维度真会炸"时开放，`explosionGuard` 那条不变）。
- **没新增配置项 / i18n 键**：沿用 `BOX_BURST = 5`。
- **已知取舍（如实记录 + 待开发者裁）**：
  1. **床仍是单放**。床是原版**两格**方块（`stepBlock` 里 `spot + spot.offset(facing)` 的双格记录就是为它写的），
     并排多放需要一整套"每张床各占两格、朝向与占位互不重叠"的排布——不是"套用同一行代码"能覆盖的。
     要不要做、按几张，等你定。
  2. 并排落点只找**同一高度**的邻格（四邻 + 四斜 + 正方向 2 格）；地面有明显高低差时会少放几个
     （`tryPlaceAll` 要求支撑面），不强行垫方块。
  3. 多放的上限仍是 `BOX_BURST = 5`，与盒内存量取小。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第八十五次同号重打。
- `DEBT.md`：不新增条目；`TD-04`（压缩盒 vs 精妙背包）继续按 R945 的口径处置（盒子供弹是它独有的优势区间）。
