---
uid: 1201c2d6
id: promaid.combat.tactics
parent: promaid.combat
name: {zh: 单兵作战战术, en: Solo Combat Tactics}
description:
  zh: >
      核心行为优先级 230 的 PVP 式近战战术接管：举盾时机、位移压制等，替代旧战斗协同。
  en: >
      A PVP-style melee tactics takeover at core-behavior priority 230: shield
      timing, positional pressure and so on, replacing the old combat
      coordination.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/MaidCombatTacticsBehavior.java, line: 1, end_line: 952}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: f3376bca39cff972c2b101a18f510a2441e57b57d1923dde439c348a285c141a
state: active
tags: [combat]
deps:
  - {kind: call, to: promaid.combat.self-preservation, label: {zh: 自保, en: Self-preservation}}
  - {kind: call, to: promaid.combat.guards, label: {zh: 友军, en: Friendly}}
---

## 单兵作战战术 · Solo Combat Tactics

核心行为优先级 230 的 PVP 式近战战术接管：举盾时机、位移压制等，替代旧战斗协同。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/MaidCombatTacticsBehavior.java`:1
