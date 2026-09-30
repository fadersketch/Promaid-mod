---
uid: 500cd4d1
id: promaid.mixin.compat
parent: promaid.mixin
name: {zh: 兼容类注入, en: Compat Patches}
description:
  zh: >
      Goety 位移聚晶推进期屏蔽走路抢方向盘；骑乘/武装拴绳相关注入（玩家下方挂点、金色标记、重锤骑乘落地缓冲、扫帚实体 travel）。
  en: >
      Suppresses walk-steering during Goety displacement-crystal thrust, plus
      ride/tether injections (hang point below the player, gold marker, mace-ride
      landing buffer, broom entity travel).
source:
  - {path: promaid_src_neo/com/maidsmart/mixin/EntityGunnerHangMixin.java, line: 1, end_line: 94}
  - {path: promaid_src_neo/com/maidsmart/mixin/EntityBroomMaidTravelMixin.java, line: 1, end_line: 168}
  - {path: promaid_src_neo/com/maidsmart/mixin/PlayerMaceRideFallMixin.java, line: 1, end_line: 124}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 824f340254531c9cb5e1eb0852816c8d41338c141cce00618eeefc4f03e8bb86
state: active
tags: [mixin, compat]
deps:
  - {kind: call, to: promaid.system.goety, label: {zh: Goety, en: Goety}}
  - {kind: call, to: promaid.combat.ride, label: {zh: 骑乘, en: Ride}}
---

## 兼容类注入 · Compat Patches

Goety 位移聚晶推进期屏蔽走路抢方向盘；骑乘/武装拴绳相关注入（玩家下方挂点、金色标记、重锤骑乘落地缓冲、扫帚实体 travel）。

**代码证据**

- `promaid_src_neo/com/maidsmart/mixin/EntityGunnerHangMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/EntityBroomMaidTravelMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/PlayerMaceRideFallMixin.java`:1
