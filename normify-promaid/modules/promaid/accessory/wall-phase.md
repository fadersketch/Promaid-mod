---
uid: 9f40aa69
id: promaid.accessory.wall-phase
parent: promaid.accessory
name: {zh: 穿墙锁敌, en: Wall-Phase Targeting}
description:
  zh: >
      佩戴「大运」的女仆能不能隔着可破坏的墙锁定敌人：沿视线逐个 raycast，路径上每一格都必须是撞得开的（硬度在 [0, 上限)
      且不带方块实体）才算看得见；夹一格基岩 / 屏障 / 黑曜石 /
      自家箱子就仍不算数。它只从全局唯一的实体版视线收口进来，故索敌（空袭扫描、威胁登记、自保反击）统一放宽，而防贴山壁顶的坐标版视线不受影响。
  en: >
      Whether a Dayun-wearing maid can target enemies through breakable walls: an
      iterative raycast walks the line of sight and every blocking cell must be
      one the breaker could punch through (hardness in [0, cap) and, with
      skip-containers on, no block entity); one unbreakable cell voids the line.
      It is reached only from the single entity-side line-of-sight funnel, so
      targeting is uniformly relaxed while the coordinate-side sight test that
      avoids hugging a cliff stays unchanged.
source:
  - {path: promaid_src_neo/com/maidsmart/accessory/DaYunWallPhase.java, line: 1, end_line: 154}
revision: 8fbb6334b1c5b0d47a7c9642f74052c0698d3b0f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 070a73f0a3ae974bd565c3efe7e711d30a43b66158a09f4126d40043c8fd1cf3
state: active
tags: [accessory]
apis:
  - protocol: rpc
    path: DaYunWallPhase.active(LivingEntity)
    description: {zh: 她此刻可以隔墙看吗（戴着 + 在飞 + 开关开）。, en: May she see through walls now (wearing + flying + switch on).}
  - protocol: rpc
    path: "DaYunWallPhase.canSeeThrough(LivingEntity, Vec3)"
    description: {zh: 这条视线路上每一格都撞得开吗。, en: Is every blocking cell along this sight line punch-through-able.}
  - protocol: rpc
    path: DaYunWallPhase.wearsDaYun(EntityMaid)
    description: {zh: 她饰品栏里有没有「大运」。, en: Does her bauble slot hold a Dayun.}
deps:
  - {kind: call, to: promaid.accessory.breaker, label: {zh: 逐格可破判据, en: Per-cell breakable predicate}}
  - {kind: call, to: promaid.accessory.maid, label: {zh: 她是不是在空袭, en: Is she on an air raid}}
  - {kind: call, to: promaid.accessory.player, label: {zh: 是不是大运, en: Is-it-Dayun predicate}}
---

## 穿墙锁敌 · Wall-Phase Targeting

佩戴「大运」的女仆能不能隔着可破坏的墙锁定敌人：沿视线逐个 raycast，路径上每一格都必须是撞得开的（硬度在 [0, 上限) 且不带方块实体）才算看得见；夹一格基岩 / 屏障 / 黑曜石 / 自家箱子就仍不算数。它只从全局唯一的实体版视线收口进来，故索敌（空袭扫描、威胁登记、自保反击）统一放宽，而防贴山壁顶的坐标版视线不受影响。

**代码证据**

- `promaid_src_neo/com/maidsmart/accessory/DaYunWallPhase.java`:1
