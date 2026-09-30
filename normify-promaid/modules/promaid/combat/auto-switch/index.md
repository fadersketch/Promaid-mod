---
uid: 12c8f19c
id: promaid.combat.auto-switch
parent: promaid.combat
name: {zh: 主动参战, en: Proactive Engagement}
description:
  zh: >
      主人被攻击或主人攻击敌对生物时，女仆自动从当前任务切到攻击任务参战，战后还原原任务；含威胁判定、任务池抽签、模组/原生任务让位、战术态重调。
  en: >
      When the owner is hit — or hits a hostile — maids auto-switch from their
      current task to a combat task and restore it afterwards. Includes threat
      scoring, a task-pool lottery, mod/native task concession and tactics
      re-tuning.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/AutoCombatSwitch.java, line: 1, end_line: 799}
  - {path: promaid_src_neo/com/maidsmart/combat/AutoCombatPools.java, line: 1, end_line: 676}
  - {path: promaid_src_neo/com/maidsmart/combat/AutoCombatTargeting.java, line: 1, end_line: 488}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 57db4901ed0cef6c0c26d0376895c3aeaa83620c1f58a84fe8a19c802626d651
state: active
tags: [combat]
---

## 主动参战 · Proactive Engagement

主人被攻击或主人攻击敌对生物时，女仆自动从当前任务切到攻击任务参战，战后还原原任务；含威胁判定、任务池抽签、模组/原生任务让位、战术态重调。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/AutoCombatSwitch.java`:1
- `promaid_src_neo/com/maidsmart/combat/AutoCombatPools.java`:1
- `promaid_src_neo/com/maidsmart/combat/AutoCombatTargeting.java`:1
