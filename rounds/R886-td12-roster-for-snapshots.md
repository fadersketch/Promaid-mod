# 实测八百八十六 · TD-12① 收口：非每 tick 的 `EntitySnapshot.of` 收敛到女仆名册

## 需求 / 来源

TD-12①：「`EntitySnapshot.of()` 仍是『整表拷贝』语义，全模组还有约 20 处**非每 tick** 路径（命令、界面、一次性扫描等）在用它」→「下轮审计非每 tick 的 `EntitySnapshot` 用法，能换名册的换」。开发者：「剩下的你逐一修复。我们必须要根除 issue37 和 38 中的问题」。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树镜像、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估（逐处审计：哪些"真的要非女仆实体"）

`EntitySnapshot.of(level)` = 把该维度整张实体表拷一遍，调用点再 `instanceof EntityMaid` 过滤。名册 `MaidRoster` 已在早前几轮引入（按 20 tick 节流、只读快照、零女仆近乎零成本），但仍有一批老站点没换。

把两树全部调用点列出来（Forge **22** 处、Neo **23** 处；Neo 多一处 `MaidShellHoming`）逐处读了循环体，得到两类：

- **循环体只处理女仆**（20 处/树）：`instanceof EntityMaid` 之后 `continue`，或只对女仆做事的——可直接换名册。
- **循环体要非女仆实体**（2/3 处）：`RideBindManager`（跳过女仆、专扫非女仆坐骑的留痕）、`FishingChairService`（找 `EntityChair` 自动坐垫）、Neo 独有 `MaidShellHoming`（找卓越前线 `Projectile` 制导弹）——**必须保留** `EntitySnapshot.of`。

结论：能换 20 处/树；`EntitySnapshot` 那条语义只为"与女仆无关的扫描"留着。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 量化 | 列出两树全部 `EntitySnapshot.of` 调用点并逐处判"是否只用到女仆" | 分类表（本轮） | ☑ |
| 2 | 实现 | `MaidRoster` 增 `inAsEntities(ServerLevel)`（按维度视图，复用名册） | 2 文件 | ☑ |
| 3 | 实现 | 20 处（两树共 40）来源表达式换成 `MaidRoster.inAsEntities(...)`，**循环体一字不动** | 19 文件/树 | ☑ |
| 4 | 验证 | 两树编译、打包、六护栏；确认只剩 2/3 处非女仆站点 | 构建输出 | ☑ |
| 5 | 留痕 | `RELEASES.tsv` 第四十六次 + 本文 + `TECH_DEBT` TD-12 收口 | 两处 | ☑ |
| 6 | 验收 | 交开发者 | 待开闸 | ☐ |

## 改法（最小改动：只换来源表达式）

老形状：

```java
for (net.minecraft.world.entity.Entity e : com.maidsmart.tool.EntitySnapshot.of(lvl)) {
    if (!(e instanceof EntityMaid m) || !m.m_6084_()) continue;
    ... 只用 m 的循环体 ...
}
```

改成：

```java
for (net.minecraft.world.entity.Entity e : com.maidsmart.tool.MaidRoster.inAsEntities(lvl)) {
    if (!(e instanceof EntityMaid m) || !m.m_6084_()) continue; // 恒真、无副作用，保留以最小化 diff
    ... 循环体原样 ...
}
```

- 新增方法：

```java
/** 某维度里活着的女仆，按 Entity 视图交出——给"循环体只用到女仆、却还写成
 *  for (Entity e : EntitySnapshot.of(lvl)) 再 instanceof 过滤"的老站点做最小改动。 */
public static List<Entity> inAsEntities(ServerLevel level) {
    if (level == null || level.m_7654_() == null) return List.of();   // Neo：level.getServer()
    List<Entity> out = new ArrayList<>();
    for (EntityMaid maid : in(level.m_7654_(), level)) out.add(maid);
    return out;
}
```

- 因为**只动来源表达式**，循环体（含 `instanceof` / `isAlive` / 各种业务判据）在**两树逐字节相同**，镜像零风险，也不触碰任何行为逻辑。
- `instanceof EntityMaid` 此后恒真——**有意保留**：删它要重写循环体（变量名 `m`/`maid`/`md` 各不同），diff 大、易错，而恒真判据无副作用。

### 有意保留（不换）

| 站点 | 为什么 |
|---|---|
| `combat/RideBindManager`（骑乘留痕清扫） | 循环**跳过女仆**、专扫非女仆坐骑的 `TAG_RIDE_MAID` / `TAG_PENDING_MARK` |
| `fishing/FishingChairService.removeOldChairs` | 找非女仆的 `EntityChair`（自动坐垫），按 owner uid 归属 |
| `combat/MaidShellHoming`（**Neo 独有**） | 找卓越前线的 `Projectile`（制导弹）；1.20.1 树无此功能 |

### 不做

- **不动**行为 / 开关 / 默认值 / 版本号；不重写循环体；不删恒真的 `instanceof`。
- 各站点上方那几行历史注释（"走 Entity.class + 有限范围 + instanceof 过滤最稳（见 MaidRoster）"）按 §1.2 保留。

## 触及文件

**工具类（两树）**：`com/maidsmart/tool/MaidRoster.java`（+`inAsEntities`、+`import Entity`）。
**改动站点（两树各 19 文件 / 20 处）**：`ProMaidExtension`、`build/{BlueprintBookNetworking,BlueprintCatalog,FarmTillDriver,IndexStoneService}`、`combat/{FriendlyFireGuard,GunnerTetherManager,MaidBombing,PetImmunityGuard}`、`command/MaidResyncCommand`、`follow/{HomePatrolHandler,MaidChunkLoadManager}`、`patrol/PatrolRevocation`、`protect/DangerEscapeHandler`、`schedule/{ScheduleManager,ScheduleNetworking,SchedulePacketsPlan}`、`task/MaidPlanting`、`tool/MaidHeldLight`。
**台账**：`TECH_DEBT.md`、`rounds/RELEASES.tsv`、`rounds/R886-*.md`（新）。

## 验证

| 项 | 结果 |
|---|---|
| 残余调用点 | 换后 `EntitySnapshot.of` 只剩：Forge **2**（RideBindManager / FishingChairService）、Neo **3**（+ MaidShellHoming 制导弹）——均**真需要非女仆实体** |
| 两树编译 | `javac EXITCODE=0` |
| 既有护栏 | `check_dev_voice` 0/0；`check_i18n_keys` 0/0；`check_preset` 0/0；`check_workflow` 0/3；`check_catch_swallow` 0/0；`check_comment_style` 931/2260；`check_panel_coverage` 0/0 |
| 打包自检 | 两树 `MISSING: none`；`lang json: OK`；Forge **17,610,091 B**（`501db5cf…e7a085`）、Neo **17,649,277 B**（`555d87a5…26cd2c`） |
| 部署 / 回归 | **未部署、未回归**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证**：把某个已换的站点改回 `EntitySnapshot.of` → 该处 `instanceof` 仍恒真、行为不变但**又走整表拷贝**；把 `RideBindManager` 误换成 `inAsEntities` → 它再也扫不到非女仆坐骑（骑乘留痕清不掉）——所以那 2/3 处**不能**换。

## 验收

**未验收**（是否接受"只换来源、保留恒真 instanceof"的最小改动口径，待开发者定）。

## 边界

- **纯收敛、无行为变化**：名册滞后 ≤20 tick；被换站点本就按 5~100 tick 或事件驱动跑，20 tick 滞后可忽略；名册只含存活女仆，与这些站点本来的 `isAlive` 判据一致（少数未判 `isAlive` 的站点——如启动清标记——丢的是已死女仆，无影响）。
- 名册是**只读快照**（先攒 `ArrayList` 再交出），在"遍历中改世界"的站点（自保撤离 / 投弹 / 排班应用）比原来的活视图更安全。
- 仍未做：TD-13②（可复现构建）。

## 台账

- **TD-12① 收口**：20 处/树 换名册，保留 2/3 处非女仆站点；②（归因更正）@八百八十已做；③ Neo 独有 `MaidShellHoming.tick` 留痕即可、不改。
