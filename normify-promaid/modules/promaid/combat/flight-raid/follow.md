---
uid: ae739c26
id: promaid.combat.flight-raid.follow
parent: promaid.combat.flight-raid
name: {zh: 飞行跟随, en: Elytra Follow-Owner}
description:
  zh: >
      主人自己飞走时，女仆背上鞘翅追上来跟随，而不是在地面垫方块。
  en: >
      When the owner flies off, she puts on elytra and follows through the air
      instead of pillaring up on the ground.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/MaidFlightFollowBehavior.java, line: 1, end_line: 1680}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: e2a5c1f49163281a6696234e1cf5b8d209fe2421d76d04e34dd48c5923172c18
state: active
tags: [combat, flight]
deps:
  - {kind: call, to: promaid.combat.flight-raid, label: {zh: 飞行套件, en: Flight kit}}
  - {kind: call, to: promaid.combat.tether, label: {zh: 拴绳, en: Tether}}
---

## 飞行跟随 · Elytra Follow-Owner

主人自己飞走时，女仆背上鞘翅追上来跟随，而不是在地面垫方块。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/MaidFlightFollowBehavior.java`:1
