---
uid: 15f98257
id: promaid.combat.guards
parent: promaid.combat
name: {zh: 友军与环境防护总闸, en: "Friendly-Fire & Environment Guards"}
description:
  zh: >
      主人与同主女仆免伤、宠物免疫（含 AOE）、友军风免、建造护盾、女仆着火不传主人、模组 TNT 不破坏方块、不踩坏农田。多为单点守卫，各自
      @EventBusSubscriber 自注册。
  en: >
      Owner and same-owner maids take no damage; pet immunity (including AOE);
      ally wind immunity; build shield; maid fire does not spread to the owner;
      modded TNT does not break blocks; farmland is never trampled. Mostly
      single-point guards, each self-registering via @EventBusSubscriber.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/FriendlyFireGuard.java, line: 1, end_line: 172}
  - {path: promaid_src_neo/com/maidsmart/combat/FriendlyWindGuard.java, line: 1, end_line: 270}
  - {path: promaid_src_neo/com/maidsmart/combat/PetImmunityGuard.java, line: 1, end_line: 207}
  - {path: promaid_src_neo/com/maidsmart/combat/MaidFireGuard.java, line: 1, end_line: 105}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 515a8a406c20395e200792bdf5d3d754a934b720b17e59aca50a678d21a2f0ba
state: active
tags: [combat, safety]
---

## 友军与环境防护总闸 · Friendly-Fire & Environment Guards

主人与同主女仆免伤、宠物免疫（含 AOE）、友军风免、建造护盾、女仆着火不传主人、模组 TNT 不破坏方块、不踩坏农田。多为单点守卫，各自 @EventBusSubscriber 自注册。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/FriendlyFireGuard.java`:1
- `promaid_src_neo/com/maidsmart/combat/FriendlyWindGuard.java`:1
- `promaid_src_neo/com/maidsmart/combat/PetImmunityGuard.java`:1
- `promaid_src_neo/com/maidsmart/combat/MaidFireGuard.java`:1
