# R1055 后置/反向炮管发射解算 + 载具内禁止近战

## 排班（开工前先填，硬门槛 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug —— 两树镜像（1.20.1 Forge + 1.21.1 NeoForge 同改）。
- **开工前读过**：`rounds/R1054`；`maid-frontline-1.20.1-0.1.3.jar` 的
  `WeaponControl.aim/tick`（弹道提前量 + `turretAutoAimFromVector`，非制导弹不传目标 UUID）；
  SWB 0.8.9.2 的 `VehicleWeaponUtils.turretAutoAimFromVector`、
  `VehicleVecUtils.getShootVec`、`VehicleEntity.getVectorFromString`；
  `fh_77bw.json` / `type_63.json` / `plz_05.json`；TLM 1.5.3 `MaidMeleeAttack` 字节码
  （`lambda$create$0` 的 swing + doHurtTarget）；本工程 `MaidMountCompat.firingSolution` /
  `tickAttack` / `MaidMeleeRiptideMixin`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 确认 jar 的火炮链路 | 非制导炮弹只有炮塔跟踪 + 弹道解算，没有弹体追踪 | ★ |
| 2 | 定位反向炮管问题 | `muzzle − dir×back` 在炮管朝后时把虚拟发射点推到车体另一侧 | ★ |
| 3 | 修发射解算 | 炮管相对车头朝后时改用真实炮口当发射点 | ★ |
| 4 | 载具内禁近战 | 载具里有武器时，不挥剑、不结算近战伤害 | ★ |
| 5 | 两树编译与护栏 | 两树 `EXITCODE=0`；14 护栏 `FAIL 0` | ★ |
| 6 | 双端回归 | `test_server` / `test_maid_load` 两套 PASS | ★ |
| 7 | 打包装机 | 双 jar 构建；4 个目标 `match=True` | ★ |
| 8 | 交开发者验收 | 自走火炮能打出炮弹；坦克内不再掏剑砍怪 | ☐ |

## 需求 / 来源

- 开发者反馈：“它7那边对于火炮有没有追踪效果？我试了试，部分载具无法正常使用，
  比如说那个自走火炮，因为其炮口和面向相反导致无法对准，还有女仆处于坦克内也是近战模式
  按理说应该拉开距离的，但她居然掏出剑在里面砍怪。”
- **jar 的火炮链路**：
  - `WeaponControl.aim` 对非制导炮弹做 `interceptTime` 提前量 + `ballisticRise` 弹道抬升；
  - `tick` 里 `turretAutoAimFromVector(aim)` 让炮塔跟踪这个解；
  - 只有 `SeekWeaponInfo != null` 的制导武器才把目标 UUID 传给 `vehicleShoot`，**普通炮弹没有弹体追踪**。
  - 因此“炮口和面向相反”不是追踪能救的：jar 同样要求炮管/炮塔能转到解算方向。

## 评估

- **级别**：P1——不崩游戏，但后置炮/反向炮塔的载具打不准或打不出；载具内近战会无视装甲。
- **根因一（后置/反向炮管）**：
  - `firingSolution` 照抄引擎的虚拟发射点 `launch = muzzle − dir × |muzzle − mount|`。
  - 这个公式假设炮口在车体**前方**。`fh_77bw` / `type_63` 的 `BarrelPos` 是负 Z
    （炮口在车尾、炮管朝后）；此时 `muzzle − dir×back` 会跨过车体落到**车头另一侧**，
    解出的方向与真实炮口到目标的连线相差一个炮管长度——直瞄炮弹就会整体偏一截。
  - 炮塔的物理转角限制（如 Type 63 的 `TurretYawRange ±15°`）仍然存在；解算修好后
    直瞄链路能把炮弹打向目标，但炮塔视觉上不一定能转过去。
- **根因二（载具内近战）**：
  - TLM 的 `MaidMeleeAttack.lambda$create$0` 只判“没拿远程武器 + 近战可达 + 目标可见”，
    **没有判乘客/载具**；女仆坐在坦克里，敌人贴到车体旁时她仍会 `swing` + `doHurtTarget`。
  - 我们自己的 `tickAttack` 同时在打车载武器，两套攻击并行；玩家看到的就是“掏剑砍怪”。

## 改法

### 1. `MaidMountCompat.firingSolution`（两树）

- 先取车头水平方向 `fwd`（Forge `Vec3.m_82498_(0, mount.getYRot())`；Neo
  `Vec3.directionFromRotation(0, mount.getYRot())`）。
- 若 `getShootVec(gun).normalize().dot(fwd) < 0`（炮管相对车头朝后）：
  `launch = muzzle`，直接从真实炮口解算；
- 否则沿用引擎的虚拟发射点 `muzzle − dir×back`（正常前射/前向炮塔保持 R1053 的口径）。
- 不改 `RangeTool.calculateFiringSolution`、弹速、重力、散布、提前量。

### 2. 载具内禁止近战（两树）

- 新增 `MaidMountCompat.suppressesMaidMelee(EntityMaid)`：
  - 她骑着**卓越前线载具**，且 `gunsFor(载具, 她)` 非空 → true；
  - 没武器的载具（卡车等）→ false，保留自卫近战。
- `MaidMeleeRiptideMixin`：
  - Forge：新增 `swing` 重定向（SRG `m_6674_`），载具内直接 return；`doHurtTarget`
    重定向里也先判 `suppressesMaidMelee`，命中则 return false。
  - Neo：在已有的 `swing` / `doHurtTarget` 两个重定向里加同一道判定。
- 近战任务本身仍由 TLM 驱动、仍会消耗攻击冷却；这里只保证**不挥剑、不结算伤害**，
  武器输出交给 `tickAttack`。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src/com/maidsmart/mixin/MaidMeleeRiptideMixin.java`
- `promaid_src_neo/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src_neo/com/maidsmart/mixin/MaidMeleeRiptideMixin.java`
- 无新类、无新配置项、无新 i18n、无新反射探针。

## 验证

- Forge `compile_promaid`：`EXITCODE=0`。
- Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`（`check_mixin_targets` 注入点 Forge 284 / Neo 145）。
- `test_server 1201`：PASS；`test_server neoforge1211`：PASS。
- `test_maid_load 1201`：PASS；`test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例全部 `match=True`。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,085,561 字节，
    SHA256 `7B14D1012F0DFB0D7D650BA6F893E39B1411FE509CF084BA282B0F70BB06D577`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,178,269 字节，
    SHA256 `C50803FFB7F3BCBE1EC815D3E695ECF195A501E1CBEF1E8D0ED23E76EA97372F`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 女仆驾驶/操作 `FH-77BW`、`PLZ-05`、`Type 63` 一类火炮，对前方目标开火：
     观察炮弹是否从炮口出发、落点是否围绕目标；不再出现“方向整体偏一截”。
  2. 若 Type 63 的炮塔因 ±15° 物理限位转不到正前方，直瞄炮弹仍应能打出去；
     只是炮塔/炮管视觉上不会转过去（这是车体数据限制，本轮不动）。
  3. 女仆坐在有武器的坦克里、让敌人贴近车体：确认她不再 `swing`/造成近战伤害，
     输出走车载机炮/主炮。
  4. 换一辆**没有武器**的卡车，确认她仍可自卫近战（本轮只拦有武器的载具）。

## 边界（没动什么）

- 不给普通炮弹加任何制导/追踪；制导武器仍沿用 `SeekWeaponInfo` + 目标 UUID。
- 不改炮塔物理转角范围、转速、车体朝向与地面绕圈半径。
- 不改飞行载具、扫帚、龙、原版坐骑的接敌与近战口径。
- 不改 R1053/R1054 的弹道映射与固定翼航线。

## 台账

- 本轮不清债；未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
