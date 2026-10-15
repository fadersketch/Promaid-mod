---
uid: 3c708425
id: promaid.combat.targeting
parent: promaid.combat
name: {zh: 飞行索敌与诊断, en: "Flight Targeting & Diagnostics"}
description:
  zh: >
      空袭状态下强制以自身为圆心半径 50 格索敌（绕过 TLM 原版丢目标），并提供 /maid_smart combat check
      自助判据检查；另挂一发**滞留探针**——走位独占咬住（或本该咬住）时把「谁在咬 / 谁没在驱动」落成一行日志，用来分清闸门问题与闸门无关的滞留。
  en: >
      Forces self-centred 50-block targeting while airborne (bypassing TLM's
      target dropping), plus the /maid_smart combat check self-test and a stall
      probe that logs who holds (or fails to drive) the maid when she should be
      moving but is not.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/FlightTargeting.java, line: 1, end_line: 462}
  - {path: promaid_src_neo/com/maidsmart/combat/CombatStallProbe.java, line: 1}
  - {path: promaid_src_neo/com/maidsmart/combat/CombatSenseCheck.java, line: 1, end_line: 329}
revision: f94c12de83c73b74b57c4d736c614ed6bca9d571
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 87aafe6af4501357e9a21e0701cfa7b8ff2418f200644ff0dc1f0702cdc3f3ad
state: active
tags: [combat, diagnostics]
deps:
  - {kind: call, to: promaid.system.work-area, label: {zh: 工作范围, en: Work range}}
  - {kind: call, to: promaid.work.tags, label: {zh: 独占标记, en: Chain-drive tag}}
  - {kind: call, to: promaid.combat.tactics, label: {zh: 驱动判定, en: Pilot check}}
  - {kind: call, to: promaid.combat.self-preservation, label: {zh: 自保判定, en: Preserve check}}
  - {kind: call, to: promaid.core.command, label: {zh: combat check, en: combat check}}
---

## 飞行索敌与诊断 · Flight Targeting & Diagnostics

空袭状态下强制以自身为圆心半径 50 格索敌（绕过 TLM 原版丢目标），并提供 /maid_smart combat check 自助判据检查；另挂一发**滞留探针**——走位独占咬住（或本该咬住）时把「谁在咬 / 谁没在驱动」落成一行日志，用来分清闸门问题与闸门无关的滞留。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/FlightTargeting.java`:1
- `promaid_src_neo/com/maidsmart/combat/CombatStallProbe.java`:1
- `promaid_src_neo/com/maidsmart/combat/CombatSenseCheck.java`:1
