---
uid: bc08a9f6
id: promaid.combat.flight-raid
parent: promaid.combat
name: {zh: 空袭（近战/远程）, en: Air Raid (Melee / Ranged)}
description:
  zh: >
      鞘翅加武器加飞行道具的模式：遇敌起跳滑翔、放烟花推进、爬升/环绕/收翅俯冲、近战一记或远程持续开火，循环。
  en: >
      Elytra plus weapon plus flight item: on spotting an enemy she launches,
      boosts with fireworks, climbs/orbits/folds her wings to dive, lands one
      melee hit or keeps firing at range, then loops.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java, line: 1, end_line: 3493}
  - {path: promaid_src_neo/com/maidsmart/combat/MaidFlightKit.java, line: 1, end_line: 1748}
  - {path: promaid_src_neo/com/maidsmart/combat/MaidFlightRangedTask.java, line: 1, end_line: 707}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 424dce7a0820ee4d447eecfff2779aa540e1fd1ca637c5f02d41164b600b0b29
state: active
tags: [combat, flight]
deps:
  - {kind: call, to: promaid.combat.targeting, label: {zh: 索敌, en: Targeting}}
  - {kind: call, to: promaid.combat.bombing, label: {zh: 轰炸, en: Bombing}}
  - {kind: call, to: promaid.combat.maneuvers, label: {zh: 战术机动, en: Maneuvers}}
---

## 空袭（近战/远程） · Air Raid (Melee / Ranged)

鞘翅加武器加飞行道具的模式：遇敌起跳滑翔、放烟花推进、爬升/环绕/收翅俯冲、近战一记或远程持续开火，循环。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`:1
- `promaid_src_neo/com/maidsmart/combat/MaidFlightKit.java`:1
- `promaid_src_neo/com/maidsmart/combat/MaidFlightRangedTask.java`:1
