---
uid: 6990a80b
id: promaid.combat.resurrect
parent: promaid.combat
name: {zh: 死亡复活与回魂符, en: "Auto-Resurrect & Soul Spell"}
description:
  zh: >
      女仆死亡 60 秒后墓碑自消并在主人出生点复活（60 秒冷却）；致死伤害且无保命物品时自动收魂符。含冷却 HUD 广播。
  en: >
      60 seconds after death her tombstone vanishes and she revives at the owner's
      spawn (60s cooldown); a lethal hit with no life-saving item auto-consumes a
      soul charm. Includes cooldown HUD broadcast.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/MaidAutoResurrect.java, line: 1, end_line: 591}
  - {path: promaid_src_neo/com/maidsmart/combat/MaidSoulSpellGuard.java, line: 1, end_line: 361}
  - {path: promaid_src_neo/com/maidsmart/combat/CooldownHudTracker.java, line: 1, end_line: 157}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 2e010eb6cde0196fd16885e710cb87eff10ab7f41918f4843f2d4bea9051d466
state: active
tags: [combat, lifecycle]
---

## 死亡复活与回魂符 · Auto-Resurrect & Soul Spell

女仆死亡 60 秒后墓碑自消并在主人出生点复活（60 秒冷却）；致死伤害且无保命物品时自动收魂符。含冷却 HUD 广播。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/MaidAutoResurrect.java`:1
- `promaid_src_neo/com/maidsmart/combat/MaidSoulSpellGuard.java`:1
- `promaid_src_neo/com/maidsmart/combat/CooldownHudTracker.java`:1
