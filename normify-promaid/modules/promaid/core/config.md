---
uid: 27872fd4
id: promaid.core.config
parent: promaid.core
name: {zh: 全模组配置定义, en: Mod Config Definition}
description:
  zh: >
      524 个设置项、22 个分类；字段声明块后跟一个约 1850 行的巨型 static 初始化块逐项重复声明。另有 22
      个一次性迁移标记被当成用户可见设置留在配置里。
  en: >
      524 settings in 22 categories. A field-declaration block is followed by a
      ~1850-line static initializer that re-declares every value. Another 22
      one-shot migration flags live in user-visible config.
source:
  - {path: promaid_src_neo/com/maidsmart/config/MaidSmartConfig.java, line: 1, end_line: 3172}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: f50a6468b16c6f70ecb42f22c13459427077910754c1e746b11f636dcffc3876
state: active
tags: [core, config]
---

## 全模组配置定义 · Mod Config Definition

524 个设置项、22 个分类；字段声明块后跟一个约 1850 行的巨型 static 初始化块逐项重复声明。另有 22 个一次性迁移标记被当成用户可见设置留在配置里。

**代码证据**

- `promaid_src_neo/com/maidsmart/config/MaidSmartConfig.java`:1
