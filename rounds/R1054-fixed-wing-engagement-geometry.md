# R1054 固定翼接敌几何：侧射炮艇高位盘旋 + 前射机通场

## 排班（开工前先填，硬门槛 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug —— 两树镜像（1.20.1 Forge + 1.21.1 NeoForge 同改）。
- **开工前读过**：`rounds/R1053`；`maid-frontline-1.20.1-0.1.3.jar` 反编译的
  `dev.maidfrontline.ai.FlightControl`（`attackPlan` 固定翼状态机、非攻击支路 ORBIT）、
  `AttackApproach`（`radius = max(80, turnRadius*1.1)`、ingress 180~300）、
  `FlightProfile`（AC-130H 巡航 2.0、转弯半径 ~73~77）、`WeaponControl`（`flightFireAllowed`
  允许 ATTACK/ORBIT/EGRESS）、`Targeting`（空中 384 / 保留 576 / A-10 900，圆心是载具）；
  SWB 0.8.9.2 的 `ac_130h.json`；本工程 `MaidAirCombat` / `FlightTargeting` /
  `RideBindManager` / `MaidMountCompat.tickAttack`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 对照 jar 的接敌几何 | 确认"绕圈大、火力丢"是航线与索敌半径问题，不是扳机数量 | ★ |
| 2 | 侧射炮艇高位盘旋 | AC-130H 在敌上方 165 格、半径 84 格切向盘旋，不再让机头追目标 | ★ |
| 3 | 前射机通场状态机 | REJOIN → ATTACK → EGRESS；只在 ATTACK 开火 | ★ |
| 4 | 索敌与接敌半径 | 固定翼 384 / 576（A-10 900）、圆心改载具；接敌 leash 450 | ★ |
| 5 | 两树编译与护栏 | 两树 `EXITCODE=0`；14 护栏 `FAIL 0` | ★ |
| 6 | 双端回归 | `test_server` / `test_maid_load` 两套 PASS | ★ |
| 7 | 打包装机 | 双 jar 构建；4 个目标 `match=True` | ★ |
| 8 | 交开发者验收 | AC-130H 侧炮持续输出；A-10/J-16/Ju-87 通场时开火 | ☐ |

## 需求 / 来源

- 开发者反馈：“女仆在开固定翼飞机飞行接敌时火力不足，绕圈范围太大导致伤害丢失严重。
  看看那个.jar是怎么处理的？”
- jar 的做法（反编译）：
  - **侧射炮艇**（AC-130H）：走 ORBIT 档，目标上方 `max(165, radius*1.1)` 格、半径 84 格，
    胡萝卜是当前方位的切向前方 60 格；侧炮在圈里持续开火，机头不需要对准目标。
  - **前射固定翼**（A-10 等）：走 ATTACK 档，ingress 在目标反方向 180~300 格，
    进入后沿通场航向直线打，距目标 50 格（A-10 40）或偏航 >45° 或超时后 EGRESS，
    拉出 130+ 格再 REJOIN；`flightFireAllowed` 在 ATTACK 里放行。
  - **索敌**：空中半径 384、保留 576（A-10 900），圆心是**载具**，不是主人。
- 我们旧版：所有固定翼统一 `bomberRunTarget`（敌正上方 +15，再叠 `aircraftAltBonus=60`，
  实际高度 +75），没有炮艇/前射机分工；索敌 50 / 维持 128、圆心是主人；接敌 leash 200。
  结果就是：AC-130H 的侧炮为了"机头对准"被迫转大圈，前射机也没有稳定的直线通场窗口。

## 评估

- **级别**：P1——不崩游戏，但固定翼的主要输出手段（侧炮/机炮/火箭）大部分时间不在射界内。
- **根因三条**：
  1. **没有角色分工**：AC-130H 是侧射平台，却被当成"从敌顶冲过去"的轰炸机；
     `aimBallistic` 在无炮塔/武器站时会调 `pointBarrelAt` 把机头掰向目标，进一步把机头
     从盘旋切线拉走，圈越绕越大。
  2. **索敌窗口太小**：固定翼一次 ingress+egress 就是 300 多格，50 格发现 / 128 格维持
     等于每趟都在半路丢目标；圆心还是主人，飞机飞出去后"主人附近"根本没有敌人。
  3. **没有开火阶段闸**：旧版只要目标有效就每一拍尝试开火，但前射机在 REJOIN/EGRESS 的
     航向与目标夹角很大，实际能命中的窗口只有擦过目标的一小段；观感就是"弹道乱飞、伤害丢失"。
- **不改弹道**：R1053 已修好 Forge 的 `firingSolution` SRG 映射；本轮只改"去哪、什么时候打"。

## 改法

### 1. `MaidAirCombat`（两树）

- 新增 `gunshipOrbitTarget(maid, mount, target)`：
  - 半径 `GUNSHIP_RADIUS=84`、高度 `GUNSHIP_ABOVE=165`、切向前瞻 `GUNSHIP_LEAD=60`；
  - 切向公式与 jar 的 `CombatMath.orbit` 同源（半径纠偏 + 切向，不做掉头对准）；
  - 胡萝卜 Y 先减 `MaidMountCompat.aircraftAltBonus()`，因为 `driveFlight` 会再加一次，
    保证实际盘旋高度就是目标上方 165 格。
- 新增 `fixedWingAttackTarget(maid, mount, target)` 状态机（按女仆 UUID 存 `FixedWingRun`）：
  - REJOIN：在目标反方向 `RUN_LINE=220` 格处生成 ingress；距 ingress <45 格，
    或距目标 <`RUN_ENTRY=240` 且机头偏角 <35° → ATTACK；
  - ATTACK：沿进入时的 `runHeading` 直线通场，胡萝卜给当前位置前方 120 格；
    距目标 <`RUN_MIN=45`、目标方位偏离 >45°、或超过 `RUN_ATTACK_TICKS=200` 拍 → EGRESS；
  - EGRESS：沿通场方向拉出 `RUN_EGRESS=140` 格；到点或 `RUN_EGRESS_TICKS=120` 拍 → REJOIN。
- 新增 `fixedWingFireAllowed(maid)`：只有 ATTACK 放行；没状态时返回 true（不影响其它调用方）。
- `clear` 里清 `FIXED_WING_RUN`，与 `ORBIT_FIRST` / `CLIMB_FIRST` 等同一生命周期。
- `bomberRunTarget` 保留（对外方法不动），但 `RideBindManager` 不再用它。

### 2. `MaidMountCompat`（两树）

- `isGunship(e)`：固定翼且实体 id 含 `ac_130` 或 `gunship`。
  - **不按座位朝向判**：实测 `ju_87.json` 的 `Orientation=180` 是尾部自卫机枪，
    按朝向会把 Ju-87 误判成炮艇。
- `isA10(e)`：实体 id 含 `a_10`（覆盖 `a_10a`），供 900 格维持半径使用。
- `entityPath(e)`：Forge 走 `ForgeRegistries.ENTITY_TYPES`、Neo 走
  `BuiltInRegistries.ENTITY_TYPE`；拿不到返回空串。
- `AIRCRAFT_ENGAGE_LEASH`：200 → 450（≈ ingress 220 + egress 140 + 余量）。
- `aircraftAltBonus()`：`private` → 包内可见，供 `MaidAirCombat` 反算盘旋高度。
- `aimBallistic`：侧射炮艇不再调用 `pointBarrelAt`（不掰机头）。
- `tickAttack`：非炮艇固定翼加 `holdFire` 闸——`MaidAirCombat.enabled()` 且不在 ATTACK
  时整段跳过开火；**只闸主驾（座位 0）**，玩家开飞机、女仆坐后座机枪时她的自卫火力照旧。
  目标写入、AI 目标 UUID、日志仍照常。炮艇不受此闸，盘旋中持续开火。

### 3. `FlightTargeting`（两树）

- `AIRCRAFT_RANGE`：50 → 384。
- 新增 `AIRCRAFT_HOLD_RANGE=576`、`A10_HOLD_RANGE=900`。
- `scanCenter`：固定翼圆心从**主人**改为**载具**（取不到才回落到她自己）。
- `keepable`：固定翼按载具水平距离 ≤ 576（A-10 900）；其它载具/扫帚仍是自身 50/128。
- 日志文案从“圆心=主人”改为“圆心=载具”。

### 4. `RideBindManager`（两树）

- 固定翼目标点分两档：
  - `gunship` → `MaidAirCombat.gunshipOrbitTarget(...)`；
  - 其它固定翼 → `MaidAirCombat.fixedWingAttackTarget(...)`；
  - 直升机/飞艇/扫帚仍 → `combatTarget(...)`。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidAirCombat.java`
- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src/com/maidsmart/combat/FlightTargeting.java`
- `promaid_src/com/maidsmart/combat/RideBindManager.java`
- `promaid_src_neo/com/maidsmart/combat/MaidAirCombat.java`
- `promaid_src_neo/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src_neo/com/maidsmart/combat/FlightTargeting.java`
- `promaid_src_neo/com/maidsmart/combat/RideBindManager.java`
- 无新类、无新配置项、无新 i18n、无新 mixin、无新反射探针。

## 验证

- Forge `compile_promaid`：`EXITCODE=0`（96 个既有警告，无新增错误）。
- Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`（含 `check_comment_style` / `check_reflection` / `check_arch`）。
- `test_server 1201`：PASS；`test_server neoforge1211`：PASS。
- `test_maid_load 1201`：PASS；`test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例全部 `match=True`。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,085,250 字节，
    SHA256 `218C15A5A32710ACCD03D838EA9C264B047B99C31FBB85580C9407D6B7C26BB8`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,178,048 字节，
    SHA256 `56268E2389C68FD065DC1E88FBA23F5DE0447CE6608E6C903C91CCBACEF640E1`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 1.20.1 Forge 实例，女仆驾驶 **AC-130H** 接敌：观察她是否稳定在目标上方约 165 格、
     以约 84 格半径横飞，M61/Bofors/M102 是否在整圈里持续出弹（而不是只在机头擦过时打几发）。
  2. 同实例换 **A-10 / J-16 / Ju-87**：观察是否形成"远离 → 直线通场 → 拉出 → 再进"的循环，
     机炮/火箭主要在直线段开火；不再出现贴脸大圈。
  3. 把目标放在离主人 200~350 格处：确认固定翼不会在半路丢目标（384/576 与 leash 450 生效）。
  4. 若 AC-130H 仍有"圈太大/高度不对"，先看日志「空战」里的
     “侧射炮艇 → 目标上方 165.0 格、半径 84.0 格盘旋”与巡航档的距目标/高差；
     若 A-10 仍不开火，看日志「固定翼攻击航线 → ATTACK/EGRESS」是否在切换。

## 边界（没动什么）

- 不改 R1053 修好的 `firingSolution` 弹道解算、弹速/散布/重力。
- 不改武器分组评分、炮位选择、RPM/锁定/安全射界、投弹最小间隔。
- 不改直升机/飞艇/扫帚/地面载具的接敌与索敌口径。
- 不改 `bomberRunTarget` 的对外签名；其它调用方未受影响。
- TOM6 按固定翼前射档处理；它若没有可用武器，本闸不会产生实际影响。

## 台账

- 本轮不清债；未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
