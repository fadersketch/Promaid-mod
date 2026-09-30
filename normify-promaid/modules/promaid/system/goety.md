---
uid: 957b4330
id: promaid.system.goety
parent: promaid.system
name: {zh: 诡厄巫法兼容, en: Goety Soft Compat}
description:
  zh: >
      全程反射软兼容。把 Goety 的风系聚晶当第三种飞行（「喷气式」）：到达/跟随/战斗三种任务；自动模式默认关（主人拉开 16 格起飞、6 格落地带回
      TLM 跟随）。两个包根都探。
  en: >
      Fully reflective soft compat. Treats Goety's wind crystal as a third kind of
      flight ('jet'): arrive/follow/combat modes; auto mode is off by default
      (launches when the owner separates 16 blocks, lands at 6 and hands back to
      TLM follow). Probes two possible package roots.
source:
  - {path: promaid_src_neo/com/maidsmart/goety/MaidGoetyCompat.java, line: 1, end_line: 418}
  - {path: promaid_src_neo/com/maidsmart/goety/MaidGoetyFlight.java, line: 1, end_line: 561}
  - {path: promaid_src_neo/com/maidsmart/goety/MaidGoetyAuto.java, line: 1, end_line: 220}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: e8b0e5f435a1e8f58b6b885002acf3fa013b29a033f9896fb7e992fb14b03c56
state: active
tags: [system, compat]
apis:
  - protocol: rpc
    path: MaidGoetyCompat.ensureHooked()
    description: {zh: 探测并挂接 Goety（两包根）。, en: Probes and hooks Goety (two package roots).}
---

## 诡厄巫法兼容 · Goety Soft Compat

全程反射软兼容。把 Goety 的风系聚晶当第三种飞行（「喷气式」）：到达/跟随/战斗三种任务；自动模式默认关（主人拉开 16 格起飞、6 格落地带回 TLM 跟随）。两个包根都探。

**代码证据**

- `promaid_src_neo/com/maidsmart/goety/MaidGoetyCompat.java`:1
- `promaid_src_neo/com/maidsmart/goety/MaidGoetyFlight.java`:1
- `promaid_src_neo/com/maidsmart/goety/MaidGoetyAuto.java`:1
