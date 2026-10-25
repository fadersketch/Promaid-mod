# R1058 固定翼目标统一 + 开火距离闸 + 末制导分档

## 排班（开工前先填，硬门槛 —— `WORKFLOW.md` §1）

- **跑道**：A 修 bug —— 两树镜像（1.20.1 Forge + 1.21.1 NeoForge）。
- **开工前读过**：`rounds/R1057` 炮艇盘旋/持续追踪；`FlightTargeting`、`RideBindManager`
  固定翼接敌段、`MaidMountCompat.tickAttack`、Neo 实例 `latest.log`（2026-10-20 13:01~13:09，
  AC-130H 实战段）；卓越前线 `ProjectileEntity.tick` / `GunItem.shootBullet` 反编译。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 统一固定翼飞行/开火目标 | 两边都走同一次 `FlightTargeting.resolve`，不再一边绕 A、一边打 B | ★ |
| 2 | 固定翼开火距离/视线闸 | 224 格内且有视线才开火；400+ 格不再空放 | ★ |
| 3 | 固定翼索敌不被 home 限制打断 | 384/576 包络内持续锁定；丢锁时擦 brain | ★ |
| 4 | Neo 末制导按载具分档 | 固定翼 600 拍/120 拍提前量；其他载具回 60 拍/40 拍 | ★ |
| 5 | 起飞助跑方向避开障碍 | 八向评分加入树/墙惩罚 | ★ |
| 6 | 两树编译与护栏 | 两树 `EXITCODE=0`；14 护栏 `FAIL 0` | ★ |
| 7 | 双端回归与装机 | 四套测试 PASS；4 个目标 `match=True` | ★ |
| 8 | 交开发者验收 | 不再远距空放；接敌后持续绕目标飞 | ☐ |

## 需求 / 来源

- 开发者反馈：“女仆在乘坐固定翼飞机飞行的时候航道上还是会有偏转问题，总是离敌人越飞越远，
  并且很难转过头来。并且总是离敌人很远的时候就开始开火，结果子弹还没有进锁敌范围没有追踪，
  导致子弹一直打在地上。”
- 同时要求：① 找出为什么不能持续追击（原设定是接敌后绕敌飞行、前方有障碍自动抬升、
  起飞方向尽量避开障碍）；② 增大子弹索敌范围/追踪时间/追踪距离，保证持续追踪直至命中；
  ③ **其他载具不要长距离追踪**——它们交战距离短，长追踪会让子弹“鬼畜”。

## 评估（日志实证）

- 同一段日志里同时出现：
  - `固定翼巡航档 … 接敌档 … 距目标=55格`（飞机在近处盘旋/跟随）；
  - `固定翼索敌 附近没有活敌人（新锁定以载具为圆心、水平 384 格）`；
  - `模组坐骑·开火 → entity.cataclysm.netherite_monstrosity`，`制导 纠向 … 距离=431~450格`。
- **根因一（目标分叉）**：飞行目标走 `FlightTargeting.resolve(maid)`，开火目标走
  `MaidMountCompat.targetOf(maid)`（直接读 brain）。TLM 的 `StartAttacking` 会在同一拍把
  `ATTACK_TARGET` 改成另一只远处的敌人，于是飞机绕近处目标转、炮弹却追 400+ 格外的 boss。
- **根因二（锁上就丢）**：固定翼 `keepable` 仍检查 `maid.isWithinRestriction()`。飞机交战包络
  是 384/576 格，home 限制通常只有几十格——目标一出家门就判 false，索敌器返回 null；
  旧版 `RideBindManager` 又从 brain 回退 `liveTargetOf`，把刚被否掉的远目标捡回来继续开火。
- **根因三（远距空放）**：`tickAttack` 没有距离闸。卓越前线炮弹寿命 40 拍（M61 18 格/拍、
  Bofors 16、M102 14 的纸面射程 640~720 格），但 400+ 格要飞 20+ 拍、下坠几十格，
  地形/提前量一点误差就全落地上。锁定范围再大也救不了“没进弹道就在打”。
- **根因四（其他载具鬼畜）**：R1057 把 Neo 末制导的 600 拍/120 拍提前量应用到了**所有载具**。
  地面载具交战距离短，炮弹一直回头找目标正是“鬼畜”的来源；固定翼从上往下打才需要长追踪。
- **根因五（起飞方向）**：`aircraftRunway` 八向只比“地面落差”，同平的几条里可能选中正对
  树林/建筑的一条，起飞段直接撞上去。

## 改法

### 1. 统一目标（两树）

- `MaidMountCompat.tickAttack`：固定翼且空战开启时，开火目标改成
  `FlightTargeting.resolve(maid)`，与 `RideBindManager` 的飞行目标完全同源。
- `RideBindManager`：固定翼在 `resolve == null` 时**不再回退 brain**；索敌器说没有就真的没有。
  其他飞行器（直升机/飞艇/扫帚）仍保留原来的 brain 回退。

### 2. 固定翼索敌（两树 `FlightTargeting`）

- 固定翼的 `keepable` / `scan` **跳过 `isWithinRestriction`**：交战包络 384 发现 / 576 维持
  （A-10 900）已经限住范围，外面还有主人 450 格接敌绳；home 限制只对非固定翼生效。
- 索敌器在 ③ 扫描冷却、④ 判定没有目标时，对固定翼调用 `eraseBrain`：
  清 `ATTACK_TARGET` / `LOOK_TARGET` 并 `setTarget(null)`，避免 TLM 或卓越前线
  “Mob 乘客自动开火”继续拿旧目标开火。

### 3. 开火距离/视线闸（两树）

- `MaidAirCombat.FIXED_WING_FIRE_RANGE = 224.0`，新增 `fixedWingInFireRange(mount, target)`
  （载具到目标碰撞箱中心的 3D 距离）。
- `tickAttack` 在固定翼上：超过 224 格、或 `SelfPreservationBehavior.hasSight(maid, target)`
  为 false → 这一拍只飞不打。侧射炮艇正常盘旋时 3D 距离约 185 格（165 高 + 84 半径），
  仍在闸内；前射机 ATTACK 通场本来就压到 220 格以内。

### 4. 末制导分档（Neo `MaidShellHoming`）

- 待认领记录从 `maid → target` 改为 `maid → (target, aircraft)`。
- 固定翼：普通弹 600 拍、提前量上限 120 拍（R1057 口径）。
- 其他载具：恢复旧版 60 拍、提前量 40 拍，避免“子弹鬼畜”。
- 制导弹仍是前 4 拍（0.2 秒）撒手，交还自己的末端制导。
- Forge 侧本来就只接管固定翼（`note` 只认 `isAircraft`），保持 600/120 不变。

### 5. 起飞方向（两树 `aircraftRunway`）

- 八向评分在“地面落差 + 悬空列”之外，再沿中心线每 3 格检查机身上方 2~12 格；
  每有一列被树冠/建筑/山壁挡住，折算 0.8 格惩罚。同样平的几条里优先选净空方向，
  不改变“找不到平地就拒绝起飞”的判据。

## 触及文件

- `promaid_src/com/maidsmart/combat/FlightTargeting.java`
- `promaid_src/com/maidsmart/combat/RideBindManager.java`
- `promaid_src/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src/com/maidsmart/combat/MaidAirCombat.java`
- `promaid_src_neo/com/maidsmart/combat/FlightTargeting.java`
- `promaid_src_neo/com/maidsmart/combat/RideBindManager.java`
- `promaid_src_neo/com/maidsmart/combat/MaidMountCompat.java`
- `promaid_src_neo/com/maidsmart/combat/MaidAirCombat.java`
- `promaid_src_neo/com/maidsmart/combat/MaidShellHoming.java`
- 无新配置项、无新 i18n、无新 mixin、无新反射探针。

## 验证

- Forge `compile_promaid`：`EXITCODE=0`；Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`。
- `test_server 1201`：PASS；`test_server neoforge1211`：PASS。
- `test_maid_load 1201`：PASS；`test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例全部 `match=True`。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,091,876 字节，
    SHA256 `68ED1FD0A73B057EB65BBD99120605A1014B24B2D40ABA5E316F85361A72FB11`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,179,949 字节，
    SHA256 `604253AA9175F55F0A28E0E4FD4C4D23D14FCD545C0F2733FDEAFE98F116A1A7`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 固定翼接敌：日志搜「固定翼索敌」与「模组坐骑·开火」，两者目标应一致；
     不应再出现“索敌器说没有活敌人、开火却指向 400+ 格外”的组合。
  2. 远距接敌：`模组坐骑·开火` 应在进入 224 格后才出现；到 400+ 格时只飞不打。
     日志搜「固定翼巡航档」看 `距目标` 是否一路收敛到 84 格圈。
  3. 其他载具（坦克/自走炮/直升机）：炮弹仍按原来的短追踪，不应出现长距离回头。
  4. 固定翼从地面起飞：日志搜「固定翼滑跑」，助跑方向应优先避开正前方的树/建筑。
  5. 炮艇正常盘旋：约 185 格 3D 距离仍在射程闸内，火力不应被误伤。

## 边界（没动什么）

- 不改 R1057 的盘旋半径 84 格、目标上方 165 格、固定旋向与比例滚转。
- 不改前射固定翼 REJOIN→ATTACK→EGRESS 几何。
- 不改非固定翼的索敌半径（50/128）与 `liveTargetOf` 回退。
- 不改 Forge 末制导的固定翼范围；Neo 恢复其他载具旧版 60/40 追踪。
- 不改弹速、重力、散布、RPM、炮塔转速；距离闸只决定“这一拍打不打”。

## 台账

- 本轮不清债；未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
