---
uid: 4b4f06ef
id: promaid.combat.auto-switch.pools
parent: promaid.combat.auto-switch
name: {zh: 战斗任务池, en: Combat Task Pools}
description:
  zh: >
      可被抽中的攻击任务集合与权重，决定她切过去用哪种打法。
  en: >
      The set of combat tasks eligible for the lottery, with weights deciding
      which fighting style she switches into.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/AutoCombatPools.java, line: 1, end_line: 676}
  - {path: promaid_src_neo/com/maidsmart/combat/CombatModeTable.java, line: 1, end_line: 280}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 4ff023c682e8974f0debdc387c707ceaf4a50fff52b8eadc4cda98b15d5d085d
state: active
tags: [combat]
deps:
  - {kind: call, to: promaid.entry.extension, label: {zh: 任务表, en: Task table}}
---

## 战斗任务池 · Combat Task Pools

可被抽中的攻击任务集合与权重，决定她切过去用哪种打法。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/AutoCombatPools.java`:1
- `promaid_src_neo/com/maidsmart/combat/CombatModeTable.java`:1
