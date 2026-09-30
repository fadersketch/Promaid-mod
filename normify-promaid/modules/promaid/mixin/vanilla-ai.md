---
uid: 55a0677a
id: promaid.mixin.vanilla-ai
parent: promaid.mixin
name: {zh: 原版 AI 注入, en: Vanilla AI Patches}
description:
  zh: >
      无主女仆总闸、走路抢方向盘抑制、原版呆滞刹车时长、空袭导航守卫、骑乘散步。
  en: >
      Ownerless-maid gate, walk-steering suppression, native idle-brake durations,
      air-raid nav guards and riding stroll.
source:
  - {path: promaid_src_neo/com/maidsmart/mixin/BehaviorOwnerlessGateMixin.java, line: 1, end_line: 74}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidMoveSuppressMixin.java, line: 1, end_line: 89}
  - {path: promaid_src_neo/com/maidsmart/mixin/MoveToTargetSinkDurationMixin.java, line: 1, end_line: 29}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 5420f47e0d63e2f58e505987296a4f3949ea694e77117520101f4f551be69986
state: active
tags: [mixin]
deps:
  - {kind: call, to: promaid.flight.free, label: {zh: 自由飞行接管, en: Free-flight takeover}}
  - {kind: call, to: promaid.system.goety, label: {zh: Goety 推进期让位, en: Goety thrust concession}}
---

## 原版 AI 注入 · Vanilla AI Patches

无主女仆总闸、走路抢方向盘抑制、原版呆滞刹车时长、空袭导航守卫、骑乘散步。

**代码证据**

- `promaid_src_neo/com/maidsmart/mixin/BehaviorOwnerlessGateMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidMoveSuppressMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MoveToTargetSinkDurationMixin.java`:1
