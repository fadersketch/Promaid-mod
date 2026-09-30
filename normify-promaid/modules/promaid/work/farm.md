---
uid: 213aa1d2
id: promaid.work.farm
parent: promaid.work
name: {zh: 锄地驱动, en: Farmland Till Driver}
description:
  zh: >
      ServerTick 模块：若「曾是耕地」的标记存在且该方块已不再是耕地，女仆在 5 乘 5 内把它锄回来。标记持久化在
      SavedData；耕作本体已回退原版 TLM。
  en: >
      A ServerTick module: if a 'was farmland' mark exists and the block is no
      longer farmland, she tills it back within 5x5. Marks persist in SavedData;
      the farming action itself was reverted to vanilla TLM.
source:
  - {path: promaid_src_neo/com/maidsmart/build/FarmTillDriver.java, line: 1, end_line: 399}
  - {path: promaid_src_neo/com/maidsmart/build/FarmSweepCache.java, line: 1, end_line: 198}
  - {path: promaid_src_neo/com/maidsmart/build/FarmlandMarkStore.java, line: 1, end_line: 88}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: f99fea9e37996942bdc4b3a8751af69efe01cb84ca55aa1b2cfcc4104b8b0d53
state: active
tags: [work, farm]
---

## 锄地驱动 · Farmland Till Driver

ServerTick 模块：若「曾是耕地」的标记存在且该方块已不再是耕地，女仆在 5 乘 5 内把它锄回来。标记持久化在 SavedData；耕作本体已回退原版 TLM。

**代码证据**

- `promaid_src_neo/com/maidsmart/build/FarmTillDriver.java`:1
- `promaid_src_neo/com/maidsmart/build/FarmSweepCache.java`:1
- `promaid_src_neo/com/maidsmart/build/FarmlandMarkStore.java`:1
