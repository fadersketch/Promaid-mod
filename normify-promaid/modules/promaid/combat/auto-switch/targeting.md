---
uid: 203e9e6d
id: promaid.combat.auto-switch.targeting
parent: promaid.combat.auto-switch
name: {zh: 参战威胁判定, en: Engagement Threat Targeting}
description:
  zh: >
      威胁评分与目标筛选：谁算威胁、值不值得切任务、打完多久撤。
  en: >
      Threat scoring and target filtering: who counts as a threat, whether it is
      worth switching, and when to disengage.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/AutoCombatTargeting.java, line: 1, end_line: 488}
  - {path: promaid_src_neo/com/maidsmart/combat/CombatWorkRange.java, line: 1, end_line: 184}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 9a1d903c1f9bda8a4178ed3266e8efeee51e5b913be19e4c4067fae8d2497d38
state: active
tags: [combat]
apis:
  - protocol: event
    path: onLivingHurt(LivingIncomingDamageEvent)
    description: {zh: 主人/女仆受击 → 威胁评分。, en: "Owner/maid hurt -> threat scoring."}
  - protocol: event
    path: onLivingDamage(...)
    description: {zh: 主人出手 → 记敌对目标。, en: "Owner attacks -> record hostile."}
deps:
  - {kind: call, to: promaid.system.work-area, label: {zh: 工作范围, en: Work range}}
  - {kind: call, to: promaid.combat.guards, label: {zh: 友军判定, en: Friendly check}}
---

## 参战威胁判定 · Engagement Threat Targeting

威胁评分与目标筛选：谁算威胁、值不值得切任务、打完多久撤。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/AutoCombatTargeting.java`:1
- `promaid_src_neo/com/maidsmart/combat/CombatWorkRange.java`:1
