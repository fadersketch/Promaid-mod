---
uid: 88d46cc9
id: promaid.combat.flight-raid.air-combat
parent: promaid.combat.flight-raid
name: {zh: 空中交战姿态, en: Air Combat Posture}
description:
  zh: >
      载具/空中状态下的悬停、机头朝向与环绕接敌的统一姿态层。
  en: >
      The shared posture layer for hovering, nose heading and orbiting engagement
      while mounted or airborne.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/MaidAirCombat.java, line: 1, end_line: 611}
  - {path: promaid_src_neo/com/maidsmart/combat/CombatOrbit.java, line: 1}
  - {path: promaid_src_neo/com/maidsmart/combat/CombatManeuver.java, line: 1}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: cd8d392bc373fa6643e12bef41d4362582db02e88e40c24bdbc6259c2565cfa4
state: active
tags: [combat]
apis:
  - protocol: rpc
    path: MaidFlightCombatBehavior.tick(...)
    description: {zh: 空袭主循环。, en: The air-raid main loop.}
  - protocol: rpc
    path: MaidFlightKit.canFly(maid)
    description: {zh: 三件套校验。, en: Validates the three-piece kit.}
---

## 空中交战姿态 · Air Combat Posture

载具/空中状态下的悬停、机头朝向与环绕接敌的统一姿态层。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/MaidAirCombat.java`:1
- `promaid_src_neo/com/maidsmart/combat/CombatOrbit.java`:1
- `promaid_src_neo/com/maidsmart/combat/CombatManeuver.java`:1
