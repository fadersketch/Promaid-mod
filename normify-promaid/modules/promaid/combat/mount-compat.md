---
uid: ae994106
id: promaid.combat.mount-compat
parent: promaid.combat
name: {zh: 载具兼容层, en: Mount Compatibility Layer}
description:
  zh: >
      3657 行的兼容模块，实际吞掉了一整个功能：原版坐骑驾驶、卓越前线载具（刹车/炮塔/弹道瞄准）、冰火传说龙的鞍位挂载。全反射，零硬依赖。
  en: >
      A 3657-line compat module that has swallowed a whole feature: vanilla mount
      driving, Superb Warfare vehicles (braking/turret/ballistic aim) and
      Ice-and-Fire dragon seat mounting. Fully reflective, zero hard dependencies.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/MaidMountCompat.java, line: 1, end_line: 4746}
  - {path: promaid_src_neo/com/maidsmart/combat/MaidShellHoming.java, line: 1, end_line: 357}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: e98ef013224e6c6915d8008d197fedb86d6d6d23834d535f713e799d50ee46db
state: active
tags: [combat, compat]
apis:
  - protocol: rpc
    path: MaidMountCompat.stopVehicle/followDist(...)
    description: {zh: 停车判据按车体尺寸。, en: Stop criteria derived from vehicle size.}
  - protocol: rpc
    path: MaidMountCompat.applyVehicleAiTargets(...)
    description: {zh: 把她的目标写进炮塔/武器位 AI 目标 UUID。, en: Writes her target into the turret/weapon-station AI target UUID.}
deps:
  - {kind: call, to: promaid.combat.flight-raid.air-combat, label: {zh: 空中姿态, en: Air posture}}
---

## 载具兼容层 · Mount Compatibility Layer

3657 行的兼容模块，实际吞掉了一整个功能：原版坐骑驾驶、卓越前线载具（刹车/炮塔/弹道瞄准）、冰火传说龙的鞍位挂载。全反射，零硬依赖。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/MaidMountCompat.java`:1
- `promaid_src_neo/com/maidsmart/combat/MaidShellHoming.java`:1
