---
uid: 3eefe0a3
id: promaid.system.work-area
parent: promaid.system
name: {zh: 工作圈钳制, en: Work-Area Clamp}
description:
  zh: >
      把 TLM 的工作圆判定统一给所有自定义行为的目标选择用，免得女仆在自定义任务里乱走。被 37 处调用。
  en: >
      Unifies TLM's work-circle predicate for every custom behavior's target
      selection, so she does not wander during custom tasks. Called from 37 sites.
source:
  - {path: promaid_src_neo/com/maidsmart/follow/WorkAreaClamp.java, line: 1, end_line: 162}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: cc2d5a67968718bfffe59fbcafbffd52bc2c4e4895bf96e215db7265d89c7acb
state: active
tags: [system, contract]
apis:
  - protocol: rpc
    path: WorkAreaClamp.clamp(...)
    description: {zh: 把目标钳进工作圆。, en: Clamps a target into the work circle.}
---

## 工作圈钳制 · Work-Area Clamp

把 TLM 的工作圆判定统一给所有自定义行为的目标选择用，免得女仆在自定义任务里乱走。被 37 处调用。

**代码证据**

- `promaid_src_neo/com/maidsmart/follow/WorkAreaClamp.java`:1
