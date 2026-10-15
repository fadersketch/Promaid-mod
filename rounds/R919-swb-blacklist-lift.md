# 实测九百一十九 卓越前线剩余载具解禁：撤掉不可驾名单 + 汤姆6 走固定翼口径 + 迷你快艇并回普通快艇

> 跑道：**B 功能解禁**——把"不可驾名单"整体撤除（汤姆 F6F `superbwarfare:tom_6` / 迷你快艇 `superbwarfare:tiny_speedboat`），两台交回正常驾驶链路；汤姆6 另按固定翼口径接管（航向直写 + 高度环 + 地形净空 + 跑道降落）。残留一条 P2 记进 `DEBT.md` **BD-24**（汤姆6 西瓜炸弹的装填语义未验证）。**为什么这轮先做功能**：开发者直接点名「不行的话就把这个也补上」，且本轮撤掉的正是`DEBT.md` §0「战斗 / 骑乘 / 载具」那条里最后一处"点名不许开"的口径；现存 P0/P1 债为 0，不存在"押着高危债做功能"。
> 开发者指令：「我记得之前已经解除了黑名单了吧？现在还是不行吗？不行的话就把这个也补上。」
> 上一轮：`rounds/R918-aircraft-landing-approach.md`（固定翼降落改进，同一会话；汤姆6 这轮复用的就是它的降落链）。

## 排班

- **本轮唯一主项**：把卓越前线剩下两台"点名不许开"的载具解禁，并保证解禁之后**真的开得动、落得下来**。直升机 / 飞艇 / 地面载具 / 坦克等既有口径一律不动。
- 步骤与完成判据：
  1. **复核名单前提**（反编译 `superbwarfare` 的 `VehicleEngineUtils`）：写出这两台引擎各自的"能不能开"判据。判据：能指出名单那条理由（"只认玩家"）对**输入通道**成立、对**能不能开**不成立。
  2. **定口径**：名单整体撤除；汤姆6 按固定翼口径接管（复用 R918 的降落链，控制通道换成直写）；迷你快艇并回地面/水面载具那套。判据：口径写成一句话、落进注释。
  3. **改 Forge 树** → 4. **镜像 Neo 树**。判据：两树 `javac` `EXITCODE=0`。
  5. **手册与 i18n 同步**（两树手册 + 两树 `en_us.json` 的 key/value）。判据：手册里不再有"这两台不开"的说法。
  6. **护栏 → 打 jar → 部署 4 实例**。判据：全护栏 `FAIL 0`；4 个目标全部 `match=True`。
- 谁验收：开发者（本轮只部署、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

- 开发者先问"黑名单是不是已经解除了"，并要求：**如果还是不行，就把这一台也补上**。
- 名单的来历（`实测七百四十七`）：六台里四架固定翼（Ju-87 / A-10 / AC-130H / KV-16）在 `实测七百七十五` 已解禁（走"定制起飞链路"），只剩汤姆 F6F 与迷你快艇，理由是"引擎里油门与姿态整段写在`乘客是玩家`那一支里，女仆是 Mob 进不去"。

## 定位：名单那条前提只对"输入通道"成立（反编译实证）

反编译 `superbwarfare` 0.8.9.1 的 `VehicleEngineUtils`（两树实体 id 逐字相同，见 `_swb_dec/engines.txt`）：

1. **迷你快艇走 `shipEngine`**：那个方法的第一道（也是**唯一**一道）闸是 `if (passenger0 == null)`，之后读位掩码写 power / 转向 —— **没有任何 `Player` 判断**。同引擎的 `speedboat` 一直能开、能打（它从来不在名单里），两者在数据上一个 `Engine=Ship, Type=Boat`、一个只多了 `MachineGun`，一字不差。**名单拦它没有依据**。
2. **汤姆 F6F 走 `tomEngine`**：输入那一段（读鼠标位、算 power/姿态）确实只在 `else if (passenger instanceof Player)` 分支里，女仆是 Mob 进不去 —— 这一点名单说对了；但**方法尾部**那段推力/升力是**无条件**的：`deltaMovement += viewVector × 0.061 × speedRate × power`，升力加在 `upVec` 上，另有滚转自稳与 `deltaRot *= 0.85`。也就是说 **直写 `power` 与 `XRot/YRot` 照样飞得起来** —— 这正是本模组自己那条汤姆6 档（`实测七百四十三·点4` 起）在做的事，也是参照模组 `maid-frontline` 的 `VehicleAdapters.tom` 在做的事。
3. **顺带发现**：全 43 台卓越前线载具里**只有 `tom_6` 一台**用 `EngineType=Tome6`（`engines.txt` 实证）——名单在 747 把它拦住之后，我们那条汤姆6 分支**从未在实机跑过**（死代码）。解禁 = 这条分支第一次上场，所以本轮把它一并按固定翼口径重写（旧版"进停车带就把 power 归零"，固定翼没有升力会掉）。

## 改法（两树镜像）

**`MaidMountCompat.java`**

- **撤名单**：删掉 `UNRIDABLE` / `UNRIDABLE_NAME` / `isUnridable` / `unridableName` 整块与 `denyReason` 最前面那道闸，原地留一段"实测九百一十九·撤除"的说明（把上面两条反编译证据写进去）。
- **固定翼口径**：新增 `isFixedWingEngine(eng) = "AIRCRAFT" || "TOM6"`，并把 `isAircraft(Entity)`（对外，`RideBindManager` / `FlightTargeting` 用）指向它 —— 汤姆6 由此继承三件事：**200 格接敌闸**（`AIRCRAFT_ENGAGE_LEASH`）、**索敌圆心 = 主人**（`FlightTargeting` 的固定翼档）、**R918 那条降落链**。`电量见底 → 安全降落` 那道闸（`isAircraftEngine(eng) && aircraftHomeLanding(...)`）同步改成 `isFixedWingEngine`。
- **降落链的直写通道**：`aircraftHomeLanding` 入口闸改 `isFixedWingEngine`；`aircraftLandFinal` 里加一个 `tom` 标记，四个写入点分叉 —— **航向**走 `forceHeadingOnto`（直写 `yaw` + `serverYaw`）而不是鼠标 X 通道，**跳过** `aircraftBank`（滚转）与 `aircraftGovernSpeed`（压速走输入位）。其余（下滑道 / 瞄准点 / 复飞 / 接地段 / 拉黑表）与固定翼共用同一条链（红线"口径只有一处"）。
- **没有规划出跑道时**：汤姆6 **不**走 `aircraftHomeLanding` 里那条"最平点"退回口径（那条全靠鼠标 / 位掩码 / 滚转角三个通道，对 Mob 乘客整段不生效，走下去就是收油门一头栽），直接 `return false` 交回巡航档继续平飞。
- **汤姆6 驾驶档重写**（`driveFlight` 的 `isTomEngine` 分支）：
  - `贴地且目标就在脚下` → 待命（power 归零），不强行起飞；
  - `贴地且要去某处` → **起飞**：抬头 **35°**（`TOM_TAKEOFF_PITCH`，原 15°）+ 推力下限 + **直接补一点竖直速度**（`AIRCRAFT_TAKEOFF_LIFT`，与固定翼起飞同一个物理后门）——升力 ∝ 当前速度，停在原地抬机头不产生升力，机头不抬则推力全平推；
  - `空中` → 航向 `forceHeadingOnto`；高度交 `aircraftHoldAltitude`（它写 `XRot`，而汤姆6 的推力在 `viewVector` 上，抬头就是爬升）；目标高度先过 `aircraftSafeTargetY` 的**地形净空线**；推力给**下限** `TOM_POWER_MAX` 而不是按距离比例（旧版进停车带归零 = 掉高度）。
- **常量收敛**：删掉不再用的 `TOM_POWER_PER_BLOCK` / `TOM_PITCH_PER_BLOCK` / `TOM_PITCH_MAX`；`TOM_TAKEOFF_PITCH` 15 → 35。

**`RideBindManager.java`**（两树各三处）

- 服务端 `recognized` 去掉 `banned` 项、客户端"吞这一下"去掉 `isUnridable` 项、每拍自愈那段 `if (isUnridable(mount)) → 解绑` 整块删除（名单没了，历史绑定不再需要"自愈解绑"）。

**手册与 i18n**（两树）

- `com/maidsmart/guide/GuideChaptersCombat.java` 三处：章节标题「七、固定翼也能开了（另两台她不开）」→「七、固定翼全都能开了」；固定翼那段的"汤姆 F6F / 迷你快艇仍不开（引擎里只认玩家）。"→"…也一样能开（走同一条链路）。"；整段「仍不开的两台」→「汤姆 F6F / 迷你快艇：这两台现在也能开了」。
- `assets/promaid/i18n/en_us.json`：上面对应的三条 **key（运行期拼接出来的中文原文）与英文 value** 同步换掉（键 = 手册正文，改文案必须一起改键，否则英文界面回落中文）。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src/com/maidsmart/combat/RideBindManager.java`
- `promaid_src/com/maidsmart/guide/GuideChaptersCombat.java`
- `promaid_src/assets/promaid/i18n/en_us.json`
- 以上四件的 Neo 镜像（`promaid_src_neo/...`）
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行的 sha256 列 + 第五十八次同号重打记载）
- `DEBT.md`（新增 BD-24 + §0 索引行）

## 验证

- **两树编译**：`compile_promaid.bat` → `EXITCODE=0`；`compile_neo.bat` → `EXITCODE=0`（过程中被 `javac` 拦下三处，见 `## 边界`）。
- **全护栏**：`check_comment_style` **PASS**（0 处）；`check_preset` / `check_catch_swallow` / `check_i18n_keys` / `check_dev_voice` / `check_comment_dup` / `check_reflection` / `check_arch` **FAIL 0 / WARN 0**；`check_workflow` **FAIL 0 / WARN 3**（历史遗留：PUBLISH_GUIDE 版本、1.2.4 归档哈希、13 个更早 jar 未登记）。
- **两个 jar**：`verify_jar_classes` **OK**（mixin 包登记 OK、注入签名 OK；`out` ↔ `jar` class 双向一致）。
- **未跑实机回归**（先停在这，等开发者指挥）。

### 部署（2026-10-13）

- 打 jar：`tools/build/build_promaid.py` → `patched/promaid-1.3.2-forge-1.20.1.jar`（**17582120** 字节，sha256 `a37b300f…b88d5d`）；`build_promaid_neo.py` → `patched/promaid-1.3.2-neoforge-1.21.1.jar`（**17618550** 字节，sha256 `765a7199…44f094`）。
- 装机：`tools/deploy/deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201）；旧包已备份在 `patched/backup_old/`。
- 留痕：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行的 sha256 列更新为新字节，并追加「第五十八次同号重打」记载；旧字节 `b5821e00…f1020a`（forge）/ `72bba604…f0a935`（neo）作废。
- 到此为止：**未跑回归、未验收、未 commit、未 push**（开发者指令："做完功能先部署，部署完等他指挥"）。

## 验收

- 状态：**未验收**（已部署@2026-10-13：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
  - 复现步骤 1（**汤姆6 开得动**）：拿骑乘指挥棒右击**汤姆 F6F**（不再出现"这架…操作门槛太高"的回执）→ 给她一个目标点 → 她应当**抬头离地、朝目标平飞**（日志搜「汤姆6起飞」/「汤姆6档」），而不是"钉在原地一动不动"。
  - 复现步骤 2（**汤姆6 落得下来**）：切进 home 模式或让她电量见底 → 她应当走**固定翼那条进近**（选条带 → 对准 → 下滑道 → 接地；搜「固定翼降落·进近 / 接地段」）；附近实在没有合格条带时应当**继续平飞**（交回巡航档），而不是收油门栽下去。
  - 复现步骤 3（**迷你快艇开得动**）：右击**迷你快艇** → 她应当与普通快艇同路：水面上开得动、能转向（搜「载具」相关日志）。
  - 复现步骤 4（**手册对上了**）：翻《Promaid 手册》第七章标题应为「固定翼全都能开了」，正文不再有"这两台不开"的说法。
  - 判定：步骤 1/2/3/4 全部符合 = 合格；异常请贴 `logs/promaid.log` / `latest.log` 里 `汤姆6` / `固定翼降落` / `骑乘指挥棒` 相关的几行。
- **已知残留（不阻断验收，记在 `DEBT.md` BD-24）**：汤姆6 的武器是**西瓜炸弹**，弹药语义是 `AutoReload:false`（弹药仓要玩家手动装填）。本轮只解禁**驾驶**；女仆能不能自动装弹 / 投弹**未验证**。
- **口径一致性说明**：`isAircraft` 把汤姆6 收进来之后，`RideBindManager` 里"主人超出接敌半径 200 格就转去追主人""有敌人时胡萝卜取**敌正上方**（轰炸航线）""玩家坐副驾且周围无敌人 → 走降落链"这三档同时作用到汤姆6 上——这正是固定翼该有的口径。其中最后一档若**选不出合格跑道**，行为与固定翼一致：退回 `holdHere`、**继续平飞**（它没有悬停能力，与"不能停"是同一条物理，不是本轮新引入的行为）。

## 边界（没动什么）

- **没动**：直升机 / 飞艇 / 地面载具 / 坦克 / 轮椅 的驱动分流；固定翼（`AIRCRAFT`）自己的巡航 / 接敌 / 起飞 / 降落**一个字节不变**（汤姆6 只是在同一批入口里多了一个 `isTomEngine` 的分叉）；`maid-frontline`（GPL-3.0）**一行代码都没抄**，只借它的思路（直写 power/姿态）——本模组是 MIT。
- **不新增开关、不改任何配置项 / 默认值**；版本号仍 **1.3.2**（`WORKFLOW.md` §5 "验收前不升号"）。
- 过程留痕（都被当场拦下并修掉，未进 jar）：
  1. 挖"汤姆6 分支"这一段时，挖块函数把**固定翼那行注释的开头一起挖掉**了（`javac` 报"非法字符 `：`"）→ 补回那半行；
  2. Neo 树里 `Vec3` 的分量是 Mojang 名（`x/y/z`），插入的代码写成了 SRG 名（`f_82479_` 等）→ 按树映射修掉；
  3. 汤姆6 分支的注释块 9 行 > `check_comment_style` 的 MEMBER 上限 8 → 前两行并成一行。
- 临时脚本（`tools/_r919*.py` / `_r919*.txt` / `_guidehit.*` / `_guidefind.*` / `_swbscan.py`）**已删**；仓库根目录既有的 `_*` 文件与 `_swb_dec/`（反编译产物，在仓库外的工作区）未动。

## 台账

- `DEBT.md`：新增 **BD-24**（P2，未清：汤姆6 西瓜炸弹的装填语义 `AutoReload:false`，女仆能否自动装弹/投弹未验证），§0 索引「战斗 / 骑乘 / 载具 / 蹲下语义」一行加入 BD-24。
- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第五十八次同号重打。
