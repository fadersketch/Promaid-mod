---
uid: 3c708425
id: promaid.combat.targeting
parent: promaid.combat
name: {zh: 飞行索敌与诊断, en: "Flight Targeting & Diagnostics"}
description:
  zh: >
      空袭状态下强制以自身为圆心半径 50 格索敌（绕过 TLM 原版丢目标），并提供一次性诊断探针与 /maid_smart combat check
      自助判据检查。
  en: >
      Forces self-centred 50-block targeting while airborne (bypassing TLM's
      target dropping), plus a one-shot diagnostic probe and the /maid_smart
      combat check self-test.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/FlightTargeting.java, line: 1, end_line: 462}
  - {path: promaid_src_neo/com/maidsmart/combat/CombatSenseCheck.java, line: 1, end_line: 329}
  - {path: promaid_src_neo/com/maidsmart/combat/FlightTargetProbe.java, line: 1, end_line: 194}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: de580df6860b01f0acd58aab56aa6e932cde2047a366661c1b19be91d5788206
state: active
tags: [combat, diagnostics]
deps:
  - {kind: call, to: promaid.system.work-area, label: {zh: 工作范围, en: Work range}}
  - {kind: call, to: promaid.core.command, label: {zh: combat check, en: combat check}}
---

## 飞行索敌与诊断 · Flight Targeting & Diagnostics

空袭状态下强制以自身为圆心半径 50 格索敌（绕过 TLM 原版丢目标），并提供一次性诊断探针与 /maid_smart combat check 自助判据检查。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/FlightTargeting.java`:1
- `promaid_src_neo/com/maidsmart/combat/CombatSenseCheck.java`:1
- `promaid_src_neo/com/maidsmart/combat/FlightTargetProbe.java`:1
