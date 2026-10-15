---
uid: d6dde494
id: promaid.accessory.breaker
parent: promaid.accessory
name: {zh: 开路破坏引擎, en: Glide Path Breaker}
description:
  zh: >
      滑翔时的清障引擎：每 tick 在佩戴者前方按速度前瞻采样，逐点清掉一个立方体内的方块（边长取
      accessory.daYunRange），跳过硬度达上限（默认黑曜石级）与可配的容器方块，掉落物照常产出。前瞻采样点加自身那格另抽成
      coveredCenters，供「撞击」复用，让两边说的「范围内」是同一片地方。
  en: >
      The clearing engine behind the glide: each tick it samples ahead of the
      wearer in proportion to speed and erases the blocks inside a cube (edge =
      accessory.daYunRange) around every sample, skipping blocks at or above the
      hardness cap (obsidian by default) and optionally containers, dropping items
      as usual. The sample cells plus the wearer's own cell are exposed as
      coveredCenters so the ram reuses the very same footprint.
source:
  - {path: promaid_src_neo/com/maidsmart/accessory/DaYunBreaker.java, line: 1, end_line: 326}
revision: f94c12de83c73b74b57c4d736c614ed6bca9d571
updated_at: "2026-10-01T00:00:00Z"
fingerprint: f9fa82fb48a9d28a747859904a85a6a00cd67f9976614fadef5c00ececd0e413
state: active
tags: [accessory]
apis:
  - protocol: rpc
    path: DaYunBreaker.sweepAhead(...)
    description: {zh: 每 tick 的前瞻清障入口（佩戴者两侧共用）。, en: Per-tick clearing entry point (shared by both wearers).}
  - protocol: rpc
    path: DaYunBreaker.coveredCenters(...)
    description: {zh: 本 tick 要清的采样格（前瞻点 + 自身那格）——与「撞击」共用。, en: Sample cells to clear this tick (ahead + self) - shared with the ram.}
  - protocol: rpc
    path: DaYunBreaker.canPhaseThrough(...)
    description: {zh: 这一格撞得开吗——破坏与穿墙锁敌共用同一判据。, en: Can this cell be punched through - breaking and wall-phase share one predicate.}
---

## 开路破坏引擎 · Glide Path Breaker

滑翔时的清障引擎：每 tick 在佩戴者前方按速度前瞻采样，逐点清掉一个立方体内的方块（边长取 accessory.daYunRange），跳过硬度达上限（默认黑曜石级）与可配的容器方块，掉落物照常产出。前瞻采样点加自身那格另抽成 coveredCenters，供「撞击」复用，让两边说的「范围内」是同一片地方。

**代码证据**

- `promaid_src_neo/com/maidsmart/accessory/DaYunBreaker.java`:1
