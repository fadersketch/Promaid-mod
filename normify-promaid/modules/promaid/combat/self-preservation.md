---
uid: 80c9e81d
id: promaid.combat.self-preservation
parent: promaid.combat
name: {zh: 自保, en: Self-Preservation}
description:
  zh: >
      任何状态下低血且有威胁时，停止手头行为转入自保：喝药/搭高/珍珠/逃跑/记录放置方块。3789 行，全模组最大的单文件。
  en: >
      At any time, when low on health with a threat present she abandons her
      current behavior and enters self-preservation: drink, pillar up, pearl,
      flee, log placed blocks. 3789 lines — the mod's largest single file.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/SelfPreservationBehavior.java, line: 1, end_line: 4696}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 1f132530e82ee2991e3eae66aaca6d7e9ed933416f12109f99d679827e61c37f
state: active
tags: [combat]
deps:
  - {kind: call, to: promaid.work.placed-block, label: {zh: 方块回收, en: Block reclaim}}
  - {kind: call, to: promaid.combat.ride, label: {zh: 骑乘, en: Ride}}
---

## 自保 · Self-Preservation

任何状态下低血且有威胁时，停止手头行为转入自保：喝药/搭高/珍珠/逃跑/记录放置方块。3789 行，全模组最大的单文件。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/SelfPreservationBehavior.java`:1
