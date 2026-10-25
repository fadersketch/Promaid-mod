# R1053 Forge 固定翼载具弹道 SRG 映射错误（发射点/重力/提前量）

## 排班（开工前先填，硬门槛 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug —— 1.20.1 Forge 单树（Neo 1.21.1 同段已正确，不改）。
- **开工前读过**：`rounds/R1052`；Forge `MaidMountCompat.firingSolution`；
  SWB 0.8.9.2 的 `VehicleWeaponUtils.turretAutoAimFromUuid` / `RangeTool.calculateFiringSolution`
  / `GunItem.shootBullet`；`srg_to_official_1.20.1.tsrg`；Neo 同段实现。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 复现/定位 | 确认不是算法差异，而是 Forge 侧发射点与目标速度输入译错 | ★ |
| 2 | 逐行对照引擎 | 五个 SRG 名字与 `turretAutoAimFromUuid` 逐字对齐 | ★ |
| 3 | 单树改 | 只改 Forge `firingSolution`，Neo 不动 | ★ |
| 4 | 编译与护栏 | Forge `EXITCODE=0`；14 护栏 `FAIL 0` | ★ |
| 5 | 专服回归 | `test_server` / `test_maid_load` 两套 PASS | ★ |
| 6 | 打包装机 | 双 jar 构建；4 个目标 `match=True` | ★ |
| 7 | 交开发者验收 | 固定翼机炮/投弹命中率与 Neo/引擎自瞄同级 | ☐ |

## 需求 / 来源

- 开发者反馈：“有人认为我们的固定翼空中载具弹道不行，没这个2准。别的都不差。”
- 对照对象：`maid-frontline-1.20.1-0.1.3.jar`（反编译）。它的 `WeaponControl.aim`
  自己解提前量/重力并用真实炮口开火；我们的设计是直接复用 SWB 引擎自己的
  `RangeTool.calculateFiringSolution`，本应同源甚至更准。
- 逐行对照后发现：Forge 树 `MaidMountCompat.firingSolution` 有 5 处 SRG 方法/字段映射错误，
  Neo 树同段是正确的。因此该问题只在 1.20.1 Forge 出现。

## 评估

- **级别**：P1——不崩游戏，但所有载具直瞄/投弹的弹道输入都被喂坏；固定翼最明显
  （炮口离车体原点最远、交战距离最长、弹道飞行时间最长）。
- **根因（五处）**：
  1. `muzzle.m_82557_(mount.m_20182_())`：`m_82557_` 是 `distanceToSqr`，被当成
     `distanceTo` 用。应为 `m_82554_`。
  2. `muzzle.m_82549_(dir.m_82490_(back))`：`m_82549_` 是 `add`，应为 `m_82546_`
     （`subtract`）。加上第 1 条，虚拟发射点从“炮口后方 d 格”变成“炮口前方 d² 格”。
  3. `Attributes.f_22276_`：该字段是 `MAX_HEALTH`，不是重力。应为
     `ForgeMod.ENTITY_GRAVITY.get()`。
  4. `targetVel.m_82492_(0.0, g, 0.0)`：`m_82492_` 是 `subtract`，应为 `m_82520_`
     （`add`）。与第 3 条叠加后，目标被当成以“最大生命值”每 tick 下坠。
  5. `targetVel.m_82520_(2.0, 1.0, 2.0)`：`m_82520_` 是 `add`，应为 `m_82542_`
     （`multiply`）。对玩家目标的提前量从“速度 ×2”变成“速度 +2”。
- **为什么固定翼最明显**：机翼/机首炮口离原点常有 4～10 格，平方后发射点前移
  16～100 格；近距离扫射时角度误差可达十几度。坦克炮塔炮口只有 1～2 格，误差小得多，
  所以“别的载具没那么明显”。
- **影响面**：`firingSolution` 同时供 `aimBallistic`（对准判定/机头与炮塔转向）、
  `directFireAt`（直瞄开火）和投弹路径的 `desired` 使用；修好后这三条一起恢复。

## 改法

- Forge `MaidMountCompat.firingSolution`：
  - `back = muzzle.m_82554_(mount.m_20182_())`；
  - `launch = muzzle.m_82546_(dir.m_82490_(back))`；
  - 重力改读 `net.minecraftforge.common.ForgeMod.ENTITY_GRAVITY.get()`；
  - `targetVel = targetVel.m_82520_(0.0, g, 0.0)`；
  - 玩家目标改 `targetVel.m_82542_(2.0, 1.0, 2.0)`；
  - 在代码旁留一行 SRG 口径注释，防止后续再次把 `distanceToSqr` 当 `distanceTo`。
- Neo 树不改（同段已是 `distanceTo/subtract/add/multiply` 与 `Attributes.GRAVITY`）。
- 不改武器选择、开火时机、安全射界或飞行机动；本轮只修弹道输入。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`
- 无新配置项、无新 i18n、无新 mixin、无新类。

## 验证

- Forge `compile_promaid`：`EXITCODE=0`（96 个既有警告，无新增错误）。
- 14 护栏全部 `FAIL 0`（`check_arch` / `check_mixin_targets` / `check_comment_style` 等）。
- `test_server 1201`：PASS；`test_server neoforge1211`：PASS。
- `test_maid_load 1201`：PASS；`test_maid_load neoforge1211`：PASS。
- 字节码复核：部署 jar 的 `firingSolution` 已改为
  `Vec3.m_82554_` / `m_82546_` / `m_82520_` / `m_82542_` 与 `ForgeMod.ENTITY_GRAVITY.get()`。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,081,984 字节，
    SHA256 `A5A759CCC7BFAD1DA8A07C888112025B794D57275CCFC3B37385764A017386FA`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,174,706 字节，
    SHA256 `CFA7B2F65645B6933687643CD7D7D8CE6A88F70CA696948232418B2AC9C8D2E3`
    （Neo 本轮未改，与 R1052 相同）
- `deploy_all.py`：4 个实例全部 `match=True`；部署前确认无 java 进程。

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 1.20.1 Forge 实例，女仆驾驶固定翼（A-10 / AC-130H 等）对地面目标机炮扫射；
  2. 对比修复前后：炮弹落点应从“明显偏前/偏低”变为围绕目标；
  3. 对玩家目标再测一次，确认提前量不再“多飞好几格”；
  4. 投弹路径同步观察落点；若仍有偏差，再查该武器是否 `ADD_SHOOTER_DELTA_MOVEMENT`
     与弹体初速/散布配置有关（本轮未动）。

## 边界（没动什么）

- 不改 `RangeTool.calculateFiringSolution` 本身，也不换成 maid-frontline 的自写解算。
- 不改武器分组评分、炮位选择、RPM/锁定/安全射界、飞行航线与盘旋逻辑。
- 不改 Neo 树；本轮目标是把 Forge 的输入修到与引擎/Neo 一致。

## 台账

- 本轮不清债；未新增债。
