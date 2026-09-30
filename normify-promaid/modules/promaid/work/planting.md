---
uid: dc619538
id: promaid.work.planting
parent: promaid.work
name: {zh: 随手种树, en: Opportunistic Replanting}
description:
  zh: >
      独立 ServerTick 模块（不是大脑行为）：女仆在伐木任务时每 20 tick 捡附近树苗，在合法泥土上按树干净空判定种一棵。
  en: >
      A standalone ServerTick module rather than a brain behavior: on the woodcut
      task she picks up nearby saplings every 20 ticks and plants one on valid
      dirt after a trunk-clearance check.
source:
  - {path: promaid_src_neo/com/maidsmart/task/MaidPlanting.java, line: 1, end_line: 755}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 5aa74debb39d4bd41fc3713815c9659b7b7674c3259d78c4ac5db0772b8c6595
state: active
tags: [work]
---

## 随手种树 · Opportunistic Replanting

独立 ServerTick 模块（不是大脑行为）：女仆在伐木任务时每 20 tick 捡附近树苗，在合法泥土上按树干净空判定种一棵。

**代码证据**

- `promaid_src_neo/com/maidsmart/task/MaidPlanting.java`:1
