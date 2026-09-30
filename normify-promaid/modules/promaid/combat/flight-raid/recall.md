---
uid: f945975d
id: promaid.combat.flight-raid.recall
parent: promaid.combat.flight-raid
name: {zh: 空袭牵引绳, en: Air-Raid Leash}
description:
  zh: >
      半径 N 格（默认 100）内找不到主人就立刻把她传送回你身边，空中也能传——防「飞太高把目标打死后自己回不来」。
  en: >
      If she cannot find the owner within N blocks (100 by default) she is
      teleported straight back, mid-air if needed, so she never strands herself
      after a kill.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/MaidFlightRecall.java, line: 1, end_line: 158}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 3615311e2568550a40c7632dd40158972810a761384c7aceaf65cfcfa4251ac8
state: active
tags: [combat]
deps:
  - {kind: call, to: promaid.system.follow, label: {zh: 传送, en: Teleport}}
---

## 空袭牵引绳 · Air-Raid Leash

半径 N 格（默认 100）内找不到主人就立刻把她传送回你身边，空中也能传——防「飞太高把目标打死后自己回不来」。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/MaidFlightRecall.java`:1
