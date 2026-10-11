# 实测八百七十七 · TD-15：每 tick 重做的反射改成"只解析一次"

## 需求 / 来源

承接 R870（TD-15 登记：反射未收敛）。开发者：「继续，一直做到所有任务结束」。

TD-15 的口径是「把探针式反射按模组收敛成'兼容类 + 在场判断'，**每 tick 路径优先**」。本片先做**每 tick 路径**那一半——把"每次调用都重做反射"的热点改成"解析一次、缓存句柄"。

约定不变：不跑回归、不写 changelog / release 说明、版本号不动（仍 **1.3.2**）、两树镜像（纯 Java 文件逐字相同，唯一差异是 SRG↔Mojang 名与加载器包名）、临时脚本用完即删、**不 commit / 不 push**（WORKFLOW §12）。

## 评估（先量后改）

全域审计（两树）结论：

- 两树 `Class.forName(` 共 **87** 处，**全部**已在 try/catch 内（多数已 `Diag.swallow` 留痕）；
- 多数 `*Compat` 已是"在场判断 + 一次性缓存句柄"范式（`GunCompat` / `ThirstCompat` / `MaidSpellCompat` / `MaidBdCompat` / `MaidMountCompat` 的 `initXxx()` 等）；
- **真正的债**是四处**未缓存**、且在 **tick 路径**上被反复调用的反射：

| # | 位置 | 症状 | 触发频率 |
|---|---|---|---|
| 1 | `MaidGoetyCompat.curios()`（212–250） | 每次 `Class.forName` + 6 个 `getMethod`，其中 `getStackInSlot` 的 `getMethod` 在**槽位循环内** | `MaidGoetyAuto.tick`→`hasFocus`，约每 10 tick（装了 Goety + Curios 时） |
| 2 | `MaidTeleportRebuild.inSubLevel()`（205–222） | 每次 `Class.forName`+`getField`+`getMethod` | `ServerEntity.sendChanges` 每 tick 路径（`MaidTeleportRebuildMixin`） |
| 3 | `CuriosElytraCompat.unwrap()`（163–179） | 每次 `getMethod("orElse")`/`getMethod("resolve")`（JDK 方法，但每调用重解析） | 每 tick（`DaYunPlayerHook` 玩家循环 / 飞行行为查鞘翅） |
| 4 | `SlashBladeCompat.bladeState()`（156–168，Forge 版） | 每次 `Class.forName(BLADESTATE).getField(...)` | 每次挥刀（受攻击冷却节流，非每 tick） |

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 审计 | 全域量反射：Class.forName 是否都在 try/catch、哪些 compat 已缓存、哪些在 tick 路径反复反射 | 审计报告（本轮） | ☑ |
| 2 | 实现 | 四处未缓存热点改为"解析一次 + 缓存句柄"（两树） | 4 类 × 2 树 | ☑ |
| 3 | 收敛 | `MaidGoetyCompat.curios()` 改调 `CuriosElytraCompat.collectAll(...)`（消除重复反射实现） | 1 公共方法（两树逐字相同） | ☑ |
| 4 | 验证 | 两树编译、打包、五护栏、注释存量不增 | 构建输出 | ☑ |
| 5 | 留痕 | `RELEASES.tsv` 第三十八次 + 本文 + `TECH_DEBT` TD-15 更新 | 两处 | ☑ |
| 6 | 验收 | 交开发者：TD-15 阶段划分（在场判断那一半是否要全铺） | 待开闸 | ☐ |

## 改法

1. **`CuriosElytraCompat`**（两树**逐字相同**；不引加载器专有类型，保持同源）：
   - 新增按类缓存的 `orElse`/`resolve` 句柄（`optionalSeenClass` / `mOptionalOrElse` / `mOptionalResolve`），`unwrap()` 不再每次 `getMethod`；
   - 新增 `public static void collectAll(LivingEntity, BiConsumer<String,ItemStack>)`：把"遍历饰品栏全部槽位"这套用缓存句柄做出来（**不做空栈过滤**，那条要调两树名字不同的 `ItemStack.isEmpty()`，留给调用方）。
2. **`MaidGoetyCompat.curios()`**（两树）：删掉自带的未缓存反射，改 `CuriosElytraCompat.collectAll(maid, (k,s) -> { if (s != null && !空) out.put(k,s); })`——键名格式 `饰品[槽]i` 与原来逐字一致（Forge `!s.m_41619_()`、Neo `!s.isEmpty()`）。
3. **`MaidTeleportRebuild.inSubLevel()`**（两树）：新增 `initSable()`，只解析一次 `Sable.HELPER` 与 `getContaining` 方法并缓存；`inSubLevel` 只 `invoke`。`ModList` 包名按树（Forge `net.minecraftforge.fml.ModList`、Neo `net.neoforged.fml`）。
4. **`SlashBladeCompat.bladeState()`**（**仅 Forge 树**）：新增字段 `bladeStateCapability`，在 `resolve()` 里存下 `BLADESTATE` capability 并纳入 `ok` 判据；`bladeState` 不再 `Class.forName`。Neo 版走的是 `BladeStateAccess.of(ItemStack)` 静态入口、句柄本就已缓存，**无须改**（两版路径不同是既有的树差异）。

### 不做

- 不给已有缓存的 compat（`GunCompat` / `ThirstCompat` / `MaidMountCompat` 的 `initXxx` 等）再加多余改动；
- 不给 `CuriosElytraCompat` 强加 `ModList.get().isLoaded("curios")`——那样会破坏它"两树逐字相同"的有意设计（该类刻意不碰加载器专有类型）；
- 不动任何玩家可见行为 / 开关 / 版本号；不做自包含 Gradle（TD-13 ②）。

## 触及文件

**代码**（两树各一份）：`com/maidsmart/combat/CuriosElytraCompat.java`（两树逐字相同）、`com/maidsmart/goety/MaidGoetyCompat.java`、`com/maidsmart/follow/MaidTeleportRebuild.java`、`com/maidsmart/combat/SlashBladeCompat.java`（Forge 侧）。
**文档 / 台账**：`TECH_DEBT.md`、`rounds/RELEASES.tsv`、`rounds/R877-*.md`（新）。

## 验证

| 项 | 结果 |
|---|---|
| 两树编译 | `javac EXITCODE=0`（两树各一次） |
| 反射收敛实证 | Forge 树 `Class.forName(` **87 → 85**；`MaidGoetyCompat.curios()` 内反射调用 **7 → 0**；`MaidTeleportRebuild.inSubLevel()` 每调用反射 **3 → 0**（解析移入只跑一次的 `initSable`） |
| 护栏 | `check_catch_swallow` FAIL 0 / WARN 0；`check_preset` FAIL 0 / WARN 2；`check_workflow` FAIL 0 / WARN 3；`check_comment_style` 931 文件 / **2262 处**（不变——先加的 9 行 javadoc 触到 MEMBER>8，已缩到 5 行）；`check_panel_coverage` FAIL 0 / WARN 0 |
| 打包自检 | 两树 `MISSING: none`、`lang json: OK`；Forge **17,603,834 B**（`4dc86f43642e…bfa825`）、Neo **17,642,964 B**（`6eb1aa7e1cb5…08074e`） |
| 镜像 | `CuriosElytraCompat` 两树 `fc /b` 逐字节相同；其余三处差异仅 SRG↔Mojmap 名 / `ModList` 包名 |
| 部署 / 回归 | **未部署、未回归**（开发者闸门） |
| commit / push | **未做**（WORKFLOW §12） |

**反证（缓存生效）**：装 Goety 时，改动前 `MaidGoetyCompat.curios()` 每次调用都 `Class.forName` 一次（异常面 = 每次抛）；改动后只在首次 `handlesForApi()` 里解析一次，后续走静态句柄——`Diag.swallow` 的 log-once 站点计数在稳态不再增长，可直接从日志验证。

## 验收

**未验收**（TD-15 是否要再做"在场判断全铺"那一半，待开发者定）。

## 边界

- **不改行为**：句柄解析从"每次"改"一次"，解析成功/失败后的返回值与原先逐一对应（失败仍退回 `null`/空集合/`Optional.empty()`）。
- 缓存键安全：`CuriosElytraCompat.unwrap` 按"第一次见到的类"缓存 `orElse/resolve`（同一运行环境里 Optional 类型唯一）；`MaidGoetyCompat.curios` 改用同类的句柄缓存，若该 compat 已 `gaveUp` 则与原先的失败面一致。
- 仍未做：`Class.forName` 作在场判断的 compat（`CuriosElytraCompat` 等）**有意保留**（为两树逐字同源）；TD-13 ②（Gradle）、TD-10、TD-09/TD-02。

## 台账

- **TD-15 部分清**（每 tick 路径优先那一半已完成，四处热点消除）；「把每处探针都改 `ModList.get()` 在场判断」那一半**有意不做全**——多处 `Class.forName` 在场判断是保持两树同源的有意设计，全铺会破坏镜像并增加树差异。
- 下一步候选：**TD-10**（开关仅 `canUse` 生效的 19 个行为）、**TD-09 / TD-02**（配置注释空洞 / 雷同）。
