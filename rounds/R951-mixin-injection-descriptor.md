# R951 女仆一生成就崩：mixin 注入点裸名字命中协变桥接（+ 注入点审计补回并接进打包）

> 跑道：承接 `rounds/R950-*.md`（建造液体后置 + 部署）。本轮来源是**开发者贴来的游戏崩溃报告**
> （Forge 实例 1.20.1-Forge_47.4.10、玩家 Khragg、TLM 1.5.3）＋一句「继续」。

## 排班

- 单轮：读崩溃报告定位 → 改 Forge 侧注入点 → 查同族隐患（全量审计）→ 把审计做成常设护栏并接进打包
  → 两树编译 → 13 护栏 → 双 jar → 部署 4/4 → 台账/轮次 → 清临时。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`）。

## 症状 / 现场

女仆一生成（右键把女仆放出来）**直接崩游戏**，崩在世界生成/实体创建阶段，游戏起不来：

```
Mixin apply failed mixins.promaid.json:MaidPickupPriorityMixin -> MaidPickupEntitiesTask:
InvalidInjectionException Invalid descriptor on
  ...@Inject::promaid$markPickupDrive(ServerLevel, EntityMaid, long, CallbackInfo)V!
  Expected (ServerLevel, LivingEntity, long, CallbackInfo)V
  but found  (ServerLevel, EntityMaid, long, CallbackInfo)V
```

栈顶是 `EntityMaid.<init>` ← `ItemSmartSlab.m_6225_`（放下刷怪蛋生成女仆那一刻）。

## 根因（一条：裸 SRG 名命中了编译器留下的协变桥接）

手工编译**没有 refmap**，`@Inject(method="…")` 里的字符串是**逐字对运行期方法名**的。而
`MaidPickupEntitiesTask` 的运行期类里 `start` **有两份**：

- TLM 自己那份：`start(ServerLevel, EntityMaid, long)`（源码里就是 `EntityMaid`）；
- 编译器自动生成的**协变桥接**：参数是父类型 `LivingEntity`，名字被重映射成同一个 SRG 名 `m_6735_`。

旧源码写的是**裸名字** `method = "m_6735_"`。Mixin 挑中的是**桥接**那份，于是"期望参数 = LivingEntity"，
而我们的处理方法声明的是 `EntityMaid` → `InvalidInjectionException` → 那个类第一次被加载（= 女仆生成）时硬崩。

崩溃报告里的 `Expected/Found` 就是判据：**Expected = Mixin 挑中的目标**、**Found = 我们的处理方法**。

## 改法

1. **崩点直修**（`promaid_src/.../MaidPickupPriorityMixin.java`，三处注入点全部写全签名）：
   `checkExtraStartConditions(ServerLevel, EntityMaid)Z`、`getItems(EntityMaid)List`、
   `start(Lnet/minecraft/server/level/ServerLevel;Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;J)V`
   —— 与 Neo 树**逐字一致**（Neo 树本来就是对的；这次崩的只是 Forge 侧）。
2. **同族隐患一并收口**（判据：目标类上"同名但签名不同"的方法存在，裸名字就会挑错。两树镜像写全签名）：

   | 文件 | 目标类上的同名不同签名 | 处理 |
   | --- | --- | --- |
   | `MaidMoveSuppressMixin` / `MaidGoetyMoveSuppressMixin` | `m_6725_`：自带 `(ServerLevel, Mob, long)` + 桥接 `(ServerLevel, LivingEntity, long)` | 写全 `Mob` 那份（Neo 侧原本就是全签名） |
   | `MoveToTargetSinkDurationMixin` | `<init>`：`()V` 与 `(II)V` 两个构造器（150/250 只在无参那个里） | 写全 `<init>()V`（Neo 侧原本就是） |
   | `MaidChatLanguageMixin` | `onSuccess`：`(ResponseChat)V` + 桥接 `(Object)V` | 写全 `ResponseChat` 那份（Neo 侧原本就是） |
   | `MaidCanAttackDefenseMixin` | `canAttack`：`(LivingEntity)Z` 与 `(LivingEntity, TargetingConditions)Z` | 写全 1 参那份（两树） |
   | `MaidContainerRemoteOpenMixin` | `stillValid`：`(Player)Z` 与 `(ContainerLevelAccess, Player, Block)Z` | 写全 1 参那份（两树） |
   | `MaidDangerPathMixin` | `getPathType`：`(PathfindingContext, III)PathType` 与 `(Mob, BlockPos)PathType` | 写全前者（两树） |

   以上六处**都不是假想**：审计脚本对每个目标类真实方法表逐个比对后报出来的（详见下节）。
3. **新增常设护栏 `tools/verify/check_mixin_targets.py`**（这正是打包脚本一直在找、却从来不存在的
   `_mixchk.py` 的角色）：
   - 目标类解析：`@Mixin(X.class)` / `targets="…"` / 多目标数组 / 一个文件里**多个 `@Mixin`**
     （每个注入点只跟**它上面最近**的那个配对）；
   - 方法表用 **`javap -p -s` 的真实 JVM 描述符**（不是 javap 的显示形态——那是 `.` 分隔、带泛型、
     写 `int` 的，跟描述符永远对不上）；
   - `method="…"` 允许**跨行字符串拼接**（`"a" + "b"`）；
   - `<init>` 只在**目标类自己**身上找（构造器不继承）；
   - 裸名字只有在目标类链上出现**多个不同签名**时才 FAIL（同名同签名的继承/覆写不算歧义）；
   - `@Inject` 还比对**处理方法参数表 = 目标参数 + 回调**（`@Local` 额外参数放行）——这条正是能抓住
     "处理器声明 EntityMaid、目标在 LivingEntity 上"的判据；
   - 自带**"已校验注入点"下限**（单棵树 100）：空转就报 FAIL，不再出现"看着在跑其实是空转"。
4. **接进打包**（两个 build 脚本）：把原来那段"探 `_mixchk.py`、探不到就 WARN 跳过"的死代码换成
   对 `tools/verify/check_mixin_targets.py` 的**硬闸门**（脚本入库了，缺了也按 FATAL）——
   不过审计就不出包。
5. **删掉一处"静默空转"的旧判据**（`tools/verify/verify_jar_classes.py` 里 v1.5.394 加的那段）：
   它从 `compile_*.txt` 抠 classpath 的正则要求 `-classpath` 后面跟着 `-d/-nowarn/-Xmaxerrs/--release`，
   而 Forge 的 argfile 里 `-classpath` 后面**直接就是源码文件清单** → 抠出来是空的 → javap 只看得见本模组
   jar 里的类 → **TLM / MC 那些外部目标类全部静默跳过**，而本次崩的正是外部类
   （`MaidPickupEntitiesTask`）。它每次都打印 OK，其实一直是空转——这就是这个崩包能出厂的直接原因。
   现在判据统一在新审计里一处实现，那段实现整块删除并留注释说明为什么删。

## 触及文件

- 两树 × `com/maidsmart/mixin/MaidPickupPriorityMixin.java`（崩点：全签名）
- Forge × `MaidMoveSuppressMixin` / `MaidGoetyMoveSuppressMixin` / `MoveToTargetSinkDurationMixin` /
  `MaidChatLanguageMixin` / `MaidCanAttackDefenseMixin` / `MaidContainerRemoteOpenMixin` / `MaidDangerPathMixin`
- Neo × `MaidCanAttackDefenseMixin` / `MaidContainerRemoteOpenMixin` / `MaidDangerPathMixin`（其余原本已是全签名）
- **新增** `tools/verify/check_mixin_targets.py`；`tools/build/build_promaid.py`、
  `tools/build/build_promaid_neo.py`（硬闸门）；`tools/verify/verify_jar_classes.py`（删空转判据）
- `rounds/RELEASES.tsv`（第九十次同号重打）+ `rounds/R951-*.md`（本文件）
- **未动**：所有 mixin 的行为逻辑（只动注解字符串）；`mixins.promaid.json` 登记；`PromaidMixinPlugin`。

## 验证

- **审计**：`check_mixin_targets.py` → `FAIL 0 / WARN 0 / 已校验注入点 265`（forge 133 + neo 132，
  含"下限 200"防空转判据）；`--tree promaid_src_neo` 单跑 → `132（下限 100）`。
- **两树编译**：`javac EXITCODE=0`。
- **护栏 13 个**：全 `FAIL 0`（新审计 + 原有 12 个；期间被 `check_comment_style` 打回两次——
  我加的解释把两个类头注释顶过 15 行上限，已压回）。
- **打包**：forge **17620637** 字节（sha256 `a01d7944…86905a`）；neoforge **17655994** 字节（`61c8873f…99456a`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 行更新 sha256 并列**第九十次**同号重打；
  R950 旧字节 `8230c123…bfc71f`（forge）/ `270561d1…af2377`（neo）作废。
- **未跑实机回归**（已部署，等开发者实测）——需实测确认：**放下女仆不再崩**（本次崩点）；
  移动抑制（站桩锁位/自走打断）、`MoveToTargetSink` 的 150→600 / 250→1200 呆滞修复、
  对话语言校验、防御反击、远程开容器、避让危险方块六项行为与旧版一致（只换注解写法，
  逻辑未动，但要确认 Mixin 确实注入到了预期的那一份重载）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- **判据**：
  - 生成女仆（刷怪蛋 / 契约 / 仪式）**不再崩**，日志里没有 `InvalidInjectionException`、
    没有 `Mixin apply failed ... MaidPickupPriorityMixin`。
  - 拾取优先级、站桩锁位、自保逃跑、战术接管、插火把、仿创造飞行六档抑制照旧生效。
  - 走路呆滞修复（走路 7.5~12.5 秒不再强制刹车）照旧生效。
  - 日志里没有 `matches more than one target` / `was not found` 一类的 Mixin 挑单告警。

## 边界（没动什么 / 已知取舍）

- **只改注解字符串，不改任何行为逻辑**：六处收口都是"把裸名字写成它本来就该指向的那一份全签名"，
  指向的方法与旧版**同一个**（旧版是运气好挑对了；本次崩的这一处是运气用完了）。
- **裸名字不一律禁止**：只有当目标类链上存在"同名不同签名"时才 FAIL。同名同签名的继承/覆写
  （如 `m_6779_` 在 EntityMaid/TamableAnimal/LivingEntity 各有一份但签名相同）不算歧义，不拦路。
- **审计只覆盖 `method="…"` / `target="…"` 里的方法说明符**，不校验 `@At(target="L…;…")` 里的**调用点**
  描述符（那是另一类混入错误，需要字节码级分析；当前没有这类历史事故）。这条已在脚本 docstring 里写明。
- **审计依赖 javap**：优先 `JAVA_HOME`，其次本机 Minecraft 运行时自带的 `java-runtime-delta`，最后 PATH。
  取不到 classpath 会记 WARN 并被下限判据拦下（不再静默跳过）。
- 旧包备份仍在 `patched/backup_old/`。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第九十次同号重打。
- `DEBT.md`：不新增条目（注入点写法缺陷本轮已收口，判据也已常设）。
