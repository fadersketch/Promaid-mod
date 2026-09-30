---
uid: a69818c6
id: promaid.work.fishing
parent: promaid.work
name: {zh: 钓鱼自动坐垫, en: Fishing Auto-Chair}
description:
  zh: >
      钓鱼任务找不到椅子/船时，扫 8 格内的空垫或水域岸边生成带标记的 TLM 坐垫并强制骑乘；每秒清理失效坐垫；被摧毁无掉落。
  en: >
      When the fishing task finds no chair or boat, she scans 8 blocks for a free
      cushion or a shoreline spot and spawns a marked TLM cushion she is forced
      onto; stale cushions are swept each second and drop nothing when destroyed.
source:
  - {path: promaid_src_neo/com/maidsmart/fishing/FishingChairService.java, line: 1, end_line: 501}
  - {path: promaid_src_neo/com/maidsmart/fishing/PlayerWaterLog.java, line: 1, end_line: 73}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 13620f5a142289b6692fe0defc39b2c20ed28799c261ea208f9801230c8bf9c7
state: active
tags: [work, compat]
---

## 钓鱼自动坐垫 · Fishing Auto-Chair

钓鱼任务找不到椅子/船时，扫 8 格内的空垫或水域岸边生成带标记的 TLM 坐垫并强制骑乘；每秒清理失效坐垫；被摧毁无掉落。

**代码证据**

- `promaid_src_neo/com/maidsmart/fishing/FishingChairService.java`:1
- `promaid_src_neo/com/maidsmart/fishing/PlayerWaterLog.java`:1
