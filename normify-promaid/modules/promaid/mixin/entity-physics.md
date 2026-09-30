---
uid: 82aa4039
id: promaid.mixin.entity-physics
parent: promaid.mixin
name: {zh: 实体与物理注入, en: "Entity & Physics Patches"}
description:
  zh: >
      外力归因（风弹/法术推人）、速度矢量拦截、骑乘门禁、重力冻结、爆炸/火焰/树叶/区块冻结等原版世界行为修补。
  en: >
      Force attribution (wind charge / spell knockback), delta-movement
      interception, mount gating, gravity freeze, plus
      explosion/fire/leaf/chunk-freeze world-behavior patches.
source:
  - {path: promaid_src_neo/com/maidsmart/mixin/EntitySetDeltaMovementMixin.java, line: 1, end_line: 41}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidBaubleTotemMixin.java, line: 1, end_line: 266}
  - {path: promaid_src_neo/com/maidsmart/mixin/ExplosionWindGuardMixin.java, line: 1, end_line: 89}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 1cd1f31566c3c2cb2901aa91bafb8e395a63fd1cd29a337894ea9f7a1cb4f5d9
state: active
tags: [mixin]
deps:
  - {kind: call, to: promaid.combat.guards, label: {zh: 友军风免, en: Friendly wind guard}}
  - {kind: call, to: promaid.work.build-exec, label: {zh: 区块冻结, en: Chunk freeze}}
---

## 实体与物理注入 · Entity & Physics Patches

外力归因（风弹/法术推人）、速度矢量拦截、骑乘门禁、重力冻结、爆炸/火焰/树叶/区块冻结等原版世界行为修补。

**代码证据**

- `promaid_src_neo/com/maidsmart/mixin/EntitySetDeltaMovementMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidBaubleTotemMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/ExplosionWindGuardMixin.java`:1
