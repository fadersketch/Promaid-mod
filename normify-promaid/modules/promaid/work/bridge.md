---
uid: 4016e075
id: promaid.work.bridge
parent: promaid.work
name: {zh: 搭路与垫高, en: "Bridge & Pillar"}
description:
  zh: >
      核心行为优先级 245：从背包拿真方块朝主人方向跨沟/爬墙铺路，含危险方块与威胁排除、以及放置方块回收。三种模式：空中搭桥、斜向跨步、垂直垫柱。
  en: >
      Core behavior at priority 245: places real blocks from her inventory to path
      toward the owner across gaps and up walls, excluding deadly blocks and
      threats, and reclaiming what she places. Three modes: air bridge, diagonal
      step and vertical pillar.
source:
  - {path: promaid_src_neo/com/maidsmart/task/BridgeUpBehavior.java, line: 1, end_line: 1300}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: fedab718b854c8bc6ba216f1ea1e3b22bd302dc12da02e636a42dae3788527e8
state: active
tags: [work]
deps:
  - {kind: call, to: promaid.work.placed-block, label: {zh: 方块回收, en: Block reclaim}}
  - {kind: call, to: promaid.core.safety, label: {zh: 选块过滤, en: Block filter}}
---

## 搭路与垫高 · Bridge & Pillar

核心行为优先级 245：从背包拿真方块朝主人方向跨沟/爬墙铺路，含危险方块与威胁排除、以及放置方块回收。三种模式：空中搭桥、斜向跨步、垂直垫柱。

**代码证据**

- `promaid_src_neo/com/maidsmart/task/BridgeUpBehavior.java`:1
