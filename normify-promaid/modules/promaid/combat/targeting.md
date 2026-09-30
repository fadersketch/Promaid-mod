---
uid: 3c708425
id: promaid.combat.targeting
parent: promaid.combat
name: {zh: 飞行索敌与诊断, en: "Flight Targeting & Diagnostics"}
description:
  zh: >
      空袭状态下强制以自身为圆心半径 50 格索敌（绕过 TLM 原版丢目标），并提供 /maid_smart combat check 自助判据检查。
  en: >
      Forces self-centred 50-block targeting while airborne (bypassing TLM's
      target dropping), plus the /maid_smart combat check self-test.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/FlightTargeting.java, line: 1, end_line: 462}
  - {path: promaid_src_neo/com/maidsmart/combat/CombatSenseCheck.java, line: 1, end_line: 329}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 0102c0ae0d170158005c3b3c0ff258db7107623923957aa320d973585ed08339
state: active
tags: [combat, diagnostics]
deps:
  - {kind: call, to: promaid.system.work-area, label: {zh: 工作范围, en: Work range}}
  - {kind: call, to: promaid.core.command, label: {zh: combat check, en: combat check}}
---

## 飞行索敌与诊断 · Flight Targeting & Diagnostics

空袭状态下强制以自身为圆心半径 50 格索敌（绕过 TLM 原版丢目标），并提供 /maid_smart combat check 自助判据检查。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/FlightTargeting.java`:1
- `promaid_src_neo/com/maidsmart/combat/CombatSenseCheck.java`:1
