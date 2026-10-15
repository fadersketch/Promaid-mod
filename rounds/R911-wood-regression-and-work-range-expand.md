# 实测九百一十一 伐木失效修复（上提回归）＋ 挖矿/伐木干活期间临时扩圈

> 跑道：**A 修 bug**（P1 回归，见 `DEBT.md` BD-15）——本轮的开轮理由与主项。
> 开发者在同一条消息里提的「干活时不被召回」（需求②）落在**同一批文件、同一个读数**上，随修随做，
> 因此本轮**不是纯功能轮**（`WORKFLOW.md` §8「P0/P1 未清期间不得开纯功能轮」照旧成立——BD-04 仍挂着未清）；
> 且本轮已同时清掉一条 P1（BD-15），配额口径两侧都满足。另记 **TD-21**（上提丢语义的结构风险）并给结论性修法。

## 排班
- 开发者的两条指令：①「最新版女仆伐木的功能似乎废掉了。彻底不会砍树了。」②「挖矿和伐木功能需要做一个限制。如果发现了目标并满足工作条件，那么这个时候的传送以及原版 TLM 中的女仆离得太远会自动走回来/传送的判定范围全会临时大幅扩大。战斗中也应有临时扩圈机制。让女仆不会一下子被传送回来或者自己走回来。」
- 定位顺序：先按"最近动过伐木的轮次"回溯（`R904`/`R905` 把挖矿/伐木上提共享基类）→ 再查扩圈的**唯一读数**在这套代码里落在哪（`EntityMaid.getRestrictRadius`，战斗那条已存在）。

## 定位（BD-15，回归）
- `MaidWorkBehavior`（上提后的共享基类）里 `m_6737_`/`canStillUse` 写着 **`uid == maid_smart:mine`**；两个子类各自的覆写在上提时被删掉。
- 于是伐木任务的 `canStillUse` **恒 false**（任务 uid 是 `maid_smart:woodcut`）：行为每 tick 刚 `start`（登记 `WOODING`、刷一行 `wood behavior start`）下一拍就被 Brain 判停 —— 扫树/寻路/砍伐一个字都不执行。
- 重构前的两份（`git show 1e1e7e24^:`）分别是 `…woodcut` / `…mine`：**同名但不同语义**的一对，正是 R905「逐成员比对」筛不到的（当时只比对了 58 个"逐字相同"候选）。
- 挖矿不受影响（写死的那份恰好是挖矿），所以现象是"只有伐木废了"。

## 修法 / 落刀清单
1. **uid 参数化（BD-15）**：基类新增 `protected abstract ResourceLocation workTaskUid()`，`canStillUse` 只读它；`MaidWoodBehavior`/`MaidMineBehavior` 各返回 `MaidWoodTask.UID` / `MaidMineTask.UID`。两侧的 `canStart` 也一并改走同一事实源——uid 字面量**全局不再出现第二处**，将来漏一边编译器直接报错。顺带把伐木那份"抄挖矿口径"的注释改成伐木口径（原注释写"挖矿行为常驻…矿挖完就站住不动"，TD-02 雷同类）。
2. **干活时临时扩圈（需求②）**：新增 `com.maidsmart.task.MaidWorkRangeExpand`（读数出口）+ `com.maidsmart.mixin.WorkRangeExpandMixin`（与既有 `CombatWorkRangeMixin` 挂同一个 `EntityMaid.getRestrictRadius`，两条都只做 max、顺序无关）。
   - 口径：`isMining() || isWooding()`（= "发现了目标、正在干活"）且 home 模式且有圈 → 读数取 `max(常态, 检索半径 + 8)`；停手（目标挖完/走空/切任务）当拍回落常态；客户端恒返回常态值（与 `CombatWorkRange` 同口径，避免画出与服务端不一致的圈）。
   - **一处改完全线一致**：TLM 的圈内判定、`SchedulePos.tick` 的"出圈（半径+4）就传送回工位、否则走回去"、寻路允许范围全读这一个值 —— 正是开发者要的"传送与走回判定范围一起临时扩大"。
   - 不新增配置项（跟随既有 `misc.workTeleportHold`）——`R902`/`aa664914` 刚做过"配置瘦身"，且该开关的语义（干活时别把她拽回去）就是这件事；注释/zh/en 文案同步改成"干活时不被召回（传送 + 工作圈临时扩圈）"。
3. **战斗那条不动**：临时扩圈在 `COMBAT_WORK_RANGE`（默认 15）里早已实现（`CombatWorkRange` + `CombatWorkRangeMixin`，实测六百一十九）；本轮只把"挖矿/伐木"这条补上同一出口。
4. 门禁/测绘同步：`tools/verify/check_preset.py` 的 `GATES_ELSEWHERE` 增 `MISC_WORK_TELEPORT_HOLD → com/maidsmart/task/MaidWorkRangeExpand.java`（接入点纳入门禁，比原来更强）；`tools/arch/arch_modules.py` 登记 2 个 neo 代表文件 + 修正该模块描述里过期的 mixin 计数（85 → 96 = 76 common + 20 client）；`ARCHITECTURE.md` 修订哈希对齐；`ARCHITECTURE-SWITCHES.md` 按生成器重刷（配置文案变了）。

## 验证
- 两树编译：`tools/build/gen_compile.py` + `gen_compile_neo.py` 重生成列表；`compile_promaid.bat` **EXITCODE=0（465 源）**、`compile_neo.bat` **EXITCODE=0（468 源）**。
- 全套护栏：`check_comment_style` **PASS**；`check_catch_swallow` / `check_comment_dup` / `check_dev_voice` / `check_i18n_keys` / `check_panel_coverage` / `check_preset` / `check_reflection` / `check_arch` 全部 **FAIL 0 / WARN 0**；`check_tree_mirror` 报告完成（只读）；`check_workflow` **FAIL 0 / WARN 3**（既有发布登记项，与本轮无关）。
- 结构数据：`python tools/arch/build_arch.py` 重生成（144 产物 + `normify.html`），`check_arch` 逐字节比对 **差异 0**、neo 未覆盖 **181 = 基线**、`ARCHITECTURE.md` 修订 `f94c12de`（= 最近一次改动源码的提交）。
- 镜像保真：两树新增文件 `fc` 逐行比对，差异仅 `m_9236_()`↔`level()`、`m_5776_()`↔`isClientSide()`、`m_21536_()`↔`hasRestriction()`、`m_21535_`↔`getRestrictRadius`（及类注里那处 SRG 名提示），无逻辑差异。
- 未跑实机回归（`WORKFLOW.md` §4 第 8 步后一律先停、等验收）。
- 未打 jar（未声明「部署」）→ `verify_jar_classes` 的"mixin 登记与注入签名"这一层留到下次构建时校验；本轮已核对 `mixins.promaid.json` 两树都登记了 `WorkRangeExpandMixin`，且注入目标名与既有 `CombatWorkRangeMixin`（实机在用）逐字一致。

### 部署（2026-10-12，开发者开闸「部署」）
- **打 jar**：`tools/build/build_promaid.py` → `patched/promaid-1.3.2-forge-1.20.1.jar`（**17584138** 字节，sha256 `083a2802…120a61`）；`build_promaid_neo.py` → `patched/promaid-1.3.2-neoforge-1.21.1.jar`（**17622212** 字节，sha256 `1a1dc898…0cd1dd`）。两树 `verify_jar_classes` **OK**——mixin 包登记 Forge **97** / Neo **96** 个类全部登记、无未登记「鸭子接口」，**mixin 注入签名无歧义/错名**（新 mixin `WorkRangeExpandMixin` 这一层由它兜住；`_mixchk.py` 不在库内、构建按约定 WARN 跳过，非本轮引入）；构建末尾全套护栏 **FAIL 0**。
- **装机**：`tools/deploy/deploy_all.py` → 4 个本机实例全部 **`match=True`**（与 `patched/` 逐字节一致）：1.21.1-NeoForge_21.1.250（17622212）/ 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201（均 17584138）。旧包已备份到 `patched/backup_old/`。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行按 §5 追加「第五十次同号重打」记录，旧字节 `56c5b863…eeb4bb` / `ddf430c3…a6f703` 作废（第四十九次 R907）。
- **到此为止**：按 `WORKFLOW.md` §4 第 8 步「第 8 步之后一律先停」，**未跑回归**（第 10 步等开发者开闸）。

## 验收
- 状态：`未验收`（已部署@2026-10-12：本机 4 实例与 `patched/` 逐字节一致；未 commit、未 push）。
  - 复现步骤 1（BD-15，**主项**）：给女仆换伐木任务 + 附近有树 → 应恢复"找树 → 走过去 → 一节节砍 → 掉落进背包/落地"；`logs/promaid.log` 里不应再只有反复的 `wood behavior start:`（应能看到 `wood leaves burst` / `wood chain burst` 这类正常留痕）。
  - 复现步骤 2（扩圈，home/排班模式）：把她的工作区设小（如 12 格），让她在 home 模式下挖矿/伐木 → 走出去找目标期间**不应**被"出圈传送"拽回工位、也不应掉头往回走；目标挖完停下后，圈与召回行为应回到常态（把她丢在远处，过一会儿应看到原版的召回生效）。关掉 `misc.workTeleportHold` 则两条都不生效（回 TLM 原版）。
  - 复现步骤 3（战斗）：home 模式下接战 → 圈按 `combat.combatWorkRange`（默认 15）放大（既有行为，本轮未改，顺带确认无回归）。
  - 判定：步骤 1 恢复砍树、步骤 2 干活期间不被召回且停手后恢复原版 = 合格；异常请把 `logs/promaid.log` 相关几行贴回。

## 边界（没动什么）
- 未改战斗扩圈的任何数值/判据；未新增配置项（复用 `misc.workTeleportHold`）；未改版本号（仍 1.3.2）；已部署本机 4 实例（§4 第 8 步）、**未跑回归**（第 10 步等开闸）、未 commit、未 push。
- 未做（有意留）：`TD-20` 里那条"启动首轮解析仍在主线程"与本轮无关；上提剩余的驱动逻辑差异（`R905` §边界）不属本轮。
- 临时脚本/日志（`_cmp_neo.log`/`_chk*.log`/`_arch*.log`/`_sw.log`）用后即删。

## 台账
- `DEBT.md`：新增 **BD-15**（P1，已清@实测九百一十一）+ §0 索引行更新；新增 **TD-21**（共享基类上提丢语义，已清@实测九百一十一，含"差异语义必须参数化 + 三方对照表"的流程约定）；**TD-19** 状态行扩容（新文件登记 + mixin 计数修正）。
- 待办（下一轮或开发者指令）：实机验收；验收后 `git commit` 并按 `WORKFLOW.md` §4 再跑一次 `python tools/arch/build_arch.py`（提交后修订哈希会再次前移，`check_arch` 会以 WARN 提示，属预期）。
