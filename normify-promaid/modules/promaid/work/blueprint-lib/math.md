---
uid: e71bb5bf
id: promaid.work.blueprint-lib.math
parent: promaid.work.blueprint-lib
name: {zh: 蓝图几何与步骤, en: "Blueprint Geometry & Steps"}
description:
  zh: >
      解析/旋转/居中/去重/裁剪/尺寸缓存；步骤是唯一权威内存形态，litematic 与 snbt 都先转成它。
  en: >
      Parse/rotate/center/dedupe/trim/size caching. The step list is the single
      authoritative in-memory form — both litematic and SNBT are converted into it
      first.
source:
  - {path: promaid_src_neo/com/maidsmart/build/BlueprintStepMath.java, line: 1, end_line: 557}
  - {path: promaid_src_neo/com/maidsmart/build/BlueprintProjectionSampler.java, line: 1, end_line: 131}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: d347b25b5d97962f02635b126d545df8f794a0597d3aff4876c3042ea7199e80
state: active
tags: [build]
apis:
  - protocol: rpc
    path: BlueprintStepMath.rotateSteps(...)
    description: {zh: 按 quarter 旋转步骤。, en: Rotates steps by quarter turns.}
  - protocol: rpc
    path: BlueprintStepMath.centerSteps(...)
    description: {zh: 把步骤居中到原点。, en: Centres steps on the origin.}
---

## 蓝图几何与步骤 · Blueprint Geometry & Steps

解析/旋转/居中/去重/裁剪/尺寸缓存；步骤是唯一权威内存形态，litematic 与 snbt 都先转成它。

**代码证据**

- `promaid_src_neo/com/maidsmart/build/BlueprintStepMath.java`:1
- `promaid_src_neo/com/maidsmart/build/BlueprintProjectionSampler.java`:1
