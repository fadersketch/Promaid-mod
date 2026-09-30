---
uid: ff44f0e1
id: promaid.work.slaughter
parent: promaid.work
name: {zh: 宰杀, en: Livestock Slaughter}
description:
  zh: >
      按 EntityType 统计附近动物，超过阈值就追上去每 3 秒杀一只（SEEK/CHASE/STRIKE 状态机）。
  en: >
      Counts nearby animals by EntityType; once a group exceeds the threshold she
      chases and kills one every 3 seconds (SEEK/CHASE/STRIKE state machine).
source:
  - {path: promaid_src_neo/com/maidsmart/task/MaidSlaughterBehavior.java, line: 1, end_line: 278}
  - {path: promaid_src_neo/com/maidsmart/task/MaidSlaughterTask.java, line: 1, end_line: 71}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 9626b03f63308117e0904e6a0cb61edf5ebb15f9a5ca573f028460e152380984
state: active
tags: [work]
---

## 宰杀 · Livestock Slaughter

按 EntityType 统计附近动物，超过阈值就追上去每 3 秒杀一只（SEEK/CHASE/STRIKE 状态机）。

**代码证据**

- `promaid_src_neo/com/maidsmart/task/MaidSlaughterBehavior.java`:1
- `promaid_src_neo/com/maidsmart/task/MaidSlaughterTask.java`:1
