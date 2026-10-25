# R1074 修固定翼炮弹提前量量纲错误（其他固定翼打不中）

## 排班

- **跑道**：A 修 bug（AC-130H 已正常，其他固定翼开火仍打空）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 读日志定位弹道 | 炮弹认领后 2 拍内下扎 40+ 格，落点在地面 | ★ |
| 2 | 反编译核对开火链 | `ShootParameters.shootDirection` 被 `GunData.shoot` 原样交给枪 | ★ |
| 3 | 定位根因 | `steerOne` 先 normalize 再加位移提前量，放大成"距离"倍 | ★ |
| 4 | 修提前量 | 先加在目标预测点上，再取方向 | ★ |
| 5 | 两树编译、14 护栏、四套测试、4 实例部署 | 全部 PASS / match=True | ★ |
| 6 | 交开发者验收 | 其他固定翼通场开火能落在目标身上 | ☐ |

## 需求 / 来源

- 开发者反馈：「现在AC130H没问题了，但其他固定翼飞机开火仍然存在问题。」

## 日志实证

- 固定翼（Ju-87）通场时 `炮=MachineGun 直瞄` 正常发射，例如
  `目标=(16.4,-46.4,-391.1) 距=202.0格 炮口=(-156.08,-0.56,-492.81)
   方向=(20.826,-6.737,12.078)`——解算本身正确（高差/提前量与
  `RangeTool.calculateFiringSolution` 逐字对得上）。
- 炮弹下一拍出现在炮口正前方 25 格（`认领 起点=(-135.10,-7.23,-480.77)`），
  但随后 `结束 ... 最后位置=(-118.68,-48.89,-477.92)`：水平只走了 37 格、
  竖直掉了 42 格，实际方向≈`(0.37,-0.93,0.06)`，与开火方向几乎正交。
- 所有 `结束` 行都落在 `y=-48～-60`、目标前方 100+ 格处——炮弹被掰成
  近乎垂直下扎，砸进目标前方的地面。

## 反编译核对

- `com.atsuishio.superbwarfare.data.gun.ShootParameters`：`shootDirection` 是
  `Vec3` 字段，构造后原样保存。
- `GunData.shoot(level, shootPosition, shootDirection, spread, zoom)` 直接转给
  `item.shoot(...)`；`VehicleEntity`/`GunItem` 那条链没有额外的炮塔变换。
  所以**开火方向这一端没有问题**，偏转发生在飞行途中。

## 根因

`MaidShellHoming.steerOne` 的提前量：

```java
want = want.normalize();                       // 单位向量（长度 1）
Vec3 aim = want.add(tv.scale(leadTicks));      // + 几十格的位移
want = aim.normalize();
```

单位向量与"目标速度 × 剩余飞行时间"（格）直接相加，量纲不一致：提前量被放大成
约"目标距离"倍。固定翼 `LEAD_CAP_AIRCRAFT=120` 拍，目标只要带一点下落速度
（监守者实测 `vy≈-1.2 格/拍`），`tv × remain` 就有 8+ 格，而单位向量的竖直分量
只有 0.07——合成后方向几乎垂直向下。AC-130H 是俯冲侧射、目标速度小，所以没暴露。

## 改法（两树 `MaidShellHoming`）

```java
Vec3 aim = to.add(tv.scale(Math.min(remain, leadCap))).subtract(from);
want = aim.lengthSqr() > 1.0E-6 ? aim.normalize() : want.normalize();
```

先把提前量加到**目标的预测位置**上，再减去炮当前位置取方向；单位向量不再参与位移
相加。转向限速、前两拍固定翼校准、只改方向不改速度这些行为全部保持不变。

## 触及文件

- `promaid_src/com/maidsmart/combat/MaidShellHoming.java`
- `promaid_src_neo/...` 同名文件镜像
- 无新配置项、无新 i18n、无新 mixin、无新类

## 验证

- Forge `compile_promaid`：`EXITCODE=0`；Neo `compile_neo`：`EXITCODE=0`。
- 14 护栏全部 `FAIL 0`。
- `test_server 1201` / `test_server neoforge1211`：PASS。
- `test_maid_load 1201` / `test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例全部 `match=True`。
- 产物：
  - Forge `patched/promaid-1.3.3-forge-1.20.1.jar` 18,105,194 字节，
    SHA256 `0EE8DB4F41647F3BB5E30DF695F64480B0E627C505A7604B6CB63A0C2A18B025`
  - Neo `patched/promaid-1.3.3-neoforge-1.21.1.jar` 18,192,625 字节，
    SHA256 `3E3CADFB89865645D1E38157DA765982EDDDB7B1B5FBB65541A91607D49C8B1C`

## 验收

- 状态：`未验收`。验收人 = 开发者；验法：
  1. 用 Ju-87 这类前射固定翼打地面监守者，看 `模组坐骑·弹道`：
     炮弹方向应与 `发射` 行的 `方向` 大致一致，不再出现 2 拍内掉 40 格。
  2. 落点应落在目标附近（目标落下时允许按提前量打在其下方）。
  3. AC-130H 侧射回归：行为应与 R1073 一致。
  4. 地面载具（M1A2 等）回归：提前量上限仍是 40 拍，短距离不应出现"鬼畜"甩动。

## 边界 / 已知取舍

- 只改末制导的瞄准点算法，不改初速、不改 `firingSolution`、不改散布。
- 提前量按"恒定速度"一阶近似，目标急变向时仍有偏差；`remain` 每拍重算，
  越飞越接近目标时提前量自然收敛。
- 不做地形遮挡判断——目标真的躲在山体后面时，炮弹仍会被地形吃掉（物理上正确）。

## 台账

- 未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
