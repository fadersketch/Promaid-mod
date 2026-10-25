# R1056 固定翼火炮末制导（撤销 R1055 载具近战闸）

## 排班（开工前先填，硬门槛 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug —— 两树镜像（1.20.1 Forge + 1.21.1 NeoForge）。
- **开工前读过**：`rounds/R1055`；Neo 现有 `MaidShellHoming`；Forge `ProMaidExtension`
  的 server tick / stop 两处旧接线；`MaidMountCompat.tickAttack` 的直瞄开火与 `fired` 汇总；
  SWB `ProjectileEntity.tick` / `GunItem.shootBullet` 的 owner 与纯抛物线行为；
  TLM `MaidMeleeAttack`（确认近战闸可以整体撤掉）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 撤销近战闸 | 两树删 `suppressesMaidMelee` 与 mixin 判定 | ★ |
| 2 | Forge 恢复末制导 | 新 `MaidShellHoming`（SRG）+ server tick/stop 接线 + `tickAttack.note` | ★ |
| 3 | Forge 限定固定翼 | Forge `note` 只接 `isAircraft`、炸弹类名跳过；Neo 保持 `isVehicle` | ★ |
| 4 | 两树编译与护栏 | 两树 `EXITCODE=0`；14 护栏 `FAIL 0` | ★ |
| 5 | 双端回归 | `test_server` / `test_maid_load` 两套 PASS | ★ |
| 6 | 打包装机 | 双 jar；4 个目标 `match=True` | ★ |
| 7 | 交开发者验收 | 固定翼机炮/炮艇炮弹能拐向移动目标 | ☐ |

## 需求 / 来源

- 开发者反馈：“近战闸没必要加；我们为了加人命中率，给固定翼上的火炮武器都加上同款追踪吧。”
- 因此本轮：
  - **撤销** R1055 的载具内禁近战（保留 R1055 的后置/反向炮管发射解算修复）；
  - 把 Neo 已有的 `MaidShellHoming` 末制导补到 Forge；Neo 原有的全载具行为保持不变，
    Forge 只新增固定翼（其他载具在 Forge 侧原本就没有末制导，保持不动）。

## 评估

- **原实现差异**：Neo 一直保留 `MaidShellHoming`（`tickAttack` 打完后 `note`，server tick
  逐拍纠向）；Forge 在 773 之后把整条链路删了，只剩直瞄。于是同一发固定翼炮弹，Neo 会拐、
  Forge 不会拐——这就是“命中率差一截”的来源。
- **为什么固定翼最需要**：固定翼速度高、开火解算很快过期；机炮/炮艇炮弹又是纯抛物线。
  末制导只改方向、不改速度，等于把“开火那一刻的解”延伸成“飞行途中持续修正”。
- **范围（按树保持原状）**：
  - Neo：`MaidShellHoming` 原本就对所有卓越前线载具生效，**保持原行为**；
  - Forge：原本没有末制导，本轮只给**固定翼**（`AIRCRAFT`/`TOM6`）新增，
    坦克/直升机/地面载具仍走直瞄，行为与之前一致；固定翼炸弹跳过。
- **近战闸**：按开发者要求整体撤销，载具内女仆恢复原来的近战行为。

## 改法

### 1. Forge 新增 `MaidShellHoming`

- 镜像 Neo 的实现，SRG 映射逐字核对：
  - `m_20148_` = getUUID、`m_20184_` = getDeltaMovement、`m_20256_` = setDeltaMovement；
  - `m_20182_` = position、`m_20191_().m_82399_()` = 包围盒中心；
  - `m_82553_` = length、`m_82556_` = lengthSqr、`m_82541_` = normalize；
  - `m_82549_` = add(Vec3)、`m_82546_` = subtract(Vec3)、`m_82490_` = scale；
  - `m_82526_` = dot、`m_165924_` = horizontalDistance；
  - `m_146922_` / `m_146926_` = setYRot / setXRot；
  - `m_19749_` = Projectile.getOwner、`m_8791_` = Level.getEntity(UUID)；
  - `m_129785_` = MinecraftServer.getAllLevels。
- 每拍把速度方向限速转 25°；普通弹最多 60 拍，制导弹 4 拍后交还自身制导；只改方向、不改速度。
- 通过类名 `com.atsuishio.superbwarfare.` + `Projectile.getOwner()` 认出“她打出去的弹”。

### 2. 接线

- Forge `ProMaidExtension`：server tick 调 `MaidShellHoming.tick(server)`；
  服务器停止调 `clearAll()`。
- Forge `MaidMountCompat.tickAttack`：`fired.length() > 0` 后调
  `MaidShellHoming.note(maid, mount, target)`。
- Neo `MaidShellHoming`：保持 `isVehicle`（所有载具）与原有炸弹处理，不改其他载具行为。
- Forge `MaidShellHoming`：`note` 只接 `isAircraft`，`claim` 跳过类名含 `bomb` 的弹体。

### 3. 撤销近战闸

- 删两树 `MaidMountCompat.suppressesMaidMelee`。
- Forge `MaidMeleeRiptideMixin` 删掉 `swing` 重定向与载具判定；
  Neo 删掉两个重定向里的载具判定。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidShellHoming.java`（新）
- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src/com/maidsmart/ProMaidExtension.java`
- `promaid_src/com/maidsmart/mixin/MaidMeleeRiptideMixin.java`
- `promaid_src_neo/com/maidsmart/combat/MaidShellHoming.java`
- `promaid_src_neo/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src_neo/com/maidsmart/mixin/MaidMeleeRiptideMixin.java`
- 无新配置项、无新 i18n、无新 mixin、无新反射探针。

## 验证

- Forge `compile_promaid`：`EXITCODE=0`（`gen_compile.py` 已把新类纳入）。
- Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`。
- `test_server 1201`：PASS；`test_server neoforge1211`：PASS。
- `test_maid_load 1201`：PASS；`test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例全部 `match=True`。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,090,779 字节，
    SHA256 `9FB287A7E9261DA27A38551D23DFB080A77FCFB3A520AB236C5F6482780EF52B`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,178,121 字节，
    SHA256 `455D7E31A0602FD444901F1BA47BAB768563F6286BDBEAE791C5F597483AAD1E`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 1.20.1 Forge 实例，女仆驾驶 A-10 / J-16 / AC-130H / Ju-87 对移动目标开火：
     观察炮弹是否在飞行途中朝目标拐（日志搜「模组坐骑·制导」应出现“接管一发炮弹/导弹”）。
  2. 对静止目标：炮弹仍应正常命中，不应出现绕圈/漂移。
  3. 炸弹投掷保持原物理（类名含 `Bomb` 的弹体不会被接管）。
  4. 其他载具：Neo 保持原有的全载具末制导；Forge 的坦克/直升机/地面载具不接管，
     行为与 R1056 之前一致。
  5. 载具内近战：按开发者要求已恢复原行为（本轮不再拦截）。

## 边界（没动什么）

- Neo 保持原有的全载具末制导；Forge 只给固定翼新增，其他载具不加。
- 不改 R1055 的后置/反向炮管发射解算。
- 不改弹速、重力、散布、RPM、炮塔转速、投弹安全高度。
- 不改制导导弹自己的 `SeekWeaponInfo` 末端制导；只在前 4 拍纠向。

## 台账

- 本轮不清债；未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
