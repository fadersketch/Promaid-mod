---
uid: bfef0d9e
id: promaid.work.tags
parent: promaid.work
name: {zh: 站桩标记（跨切面）, en: Work-Still Tagging (cross-cutting)}
description:
  zh: >
      persistentData 标记（WORK_STILL_TAG 与 BUILD_SIT_TAG）告诉 mixin 抑制移动目标下沉；被 17 处
      mixin 引用。是本包最重要的集成契约。
  en: >
      persistentData flags (WORK_STILL_TAG and BUILD_SIT_TAG) tell mixins to
      suppress MoveToTargetSink; referenced by 17 mixin sites. The single most
      important integration contract in the package.
source:
  - {path: promaid_src_neo/com/maidsmart/task/MaidWorkTags.java, line: 1, end_line: 195}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 6b6780f176b28acc9049a941e617e49767d87142327cf1d54d075129544bdde5
state: active
tags: [work, contract]
apis:
  - protocol: dataflow
    path: MaidWorkTags.WORK_STILL_TAG
    description: {zh: 站桩契约标记。, en: The work-still contract tag.}
  - protocol: dataflow
    path: MaidWorkTags.BUILD_SIT_TAG
    description: {zh: 建造期就座契约标记。, en: The build-sit contract tag.}
---

## 站桩标记（跨切面） · Work-Still Tagging (cross-cutting)

persistentData 标记（WORK_STILL_TAG 与 BUILD_SIT_TAG）告诉 mixin 抑制移动目标下沉；被 17 处 mixin 引用。是本包最重要的集成契约。

**代码证据**

- `promaid_src_neo/com/maidsmart/task/MaidWorkTags.java`:1
