---
uid: 232c7aed
id: promaid.work.index-stone
parent: promaid.work
name: {zh: 指标石, en: Index Stone}
description:
  zh: >
      物品加 512 格长射线锁块加女仆绑定；两者齐备时，女仆起点与锁定块之间的所有空气变成一次性临时桥，她靠传送逐格填。客户端与服务端共用同一套
      IndexStonePlan 几何。
  en: >
      An item plus a 512-block raycast block lock plus a maid binding: when both
      are set, every air block between her start point and the locked block
      becomes a one-shot temporary bridge she fills by teleporting. Client and
      server share the same IndexStonePlan geometry.
source:
  - {path: promaid_src_neo/com/maidsmart/build/IndexStoneService.java, line: 1, end_line: 726}
  - {path: promaid_src_neo/com/maidsmart/build/IndexStoneBuildBehavior.java, line: 1, end_line: 388}
  - {path: promaid_src_neo/com/maidsmart/build/IndexStonePreviewClient.java, line: 1, end_line: 409}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 0d31bb8811e60336717d0e4a38d4e585356d5730326bd581909b6a5c05bb1f5c
state: active
tags: [build, item]
deps:
  - {kind: call, to: promaid.work.build-exec, label: {zh: 放置逻辑, en: Placement}}
  - {kind: call, to: promaid.work.placed-block, label: {zh: 回收, en: Reclaim}}
---

## 指标石 · Index Stone

物品加 512 格长射线锁块加女仆绑定；两者齐备时，女仆起点与锁定块之间的所有空气变成一次性临时桥，她靠传送逐格填。客户端与服务端共用同一套 IndexStonePlan 几何。

**代码证据**

- `promaid_src_neo/com/maidsmart/build/IndexStoneService.java`:1
- `promaid_src_neo/com/maidsmart/build/IndexStoneBuildBehavior.java`:1
- `promaid_src_neo/com/maidsmart/build/IndexStonePreviewClient.java`:1
