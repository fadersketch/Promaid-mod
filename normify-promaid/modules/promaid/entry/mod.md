---
uid: 322fa5b9
id: promaid.entry.mod
parent: promaid.entry
name: {zh: 模组主类与初始化, en: "Mod Main & Bootstrap"}
description:
  zh: >
      注解 @Mod 的入口：注册物品、物品/网络/TaskData 引导、配置 SPEC 装配，以及一段约 320 行的历史默认值迁移链（含 22 个
      *_MIGRATED 一次性标记）。
  en: >
      The @Mod entry: registers items, bootstraps item/network/task-data,
      assembles the config SPEC, and runs a ~320-line chain of historical
      default-value migrations (including 22 one-shot *_MIGRATED flags).
source:
  - {path: promaid_src_neo/com/maidsmart/ProMaidMod.java, line: 1, end_line: 452}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 15fdf0a6f075685e1558cb87a803c22c95d0a05c2137603eb647811956c5c986
state: active
tags: [entry]
apis:
  - protocol: event
    path: ProMaidMod()
    description: {zh: 模组构造：注册物品与配置。, en: "Mod constructor: registers items and config."}
  - protocol: event
    path: runConfigMigration()
    description: {zh: "历史默认值迁移链（幂等，靠 *_MIGRATED 标记）。", en: "Historical default migration chain (idempotent via *_MIGRATED flags)."}
---

## 模组主类与初始化 · Mod Main & Bootstrap

注解 @Mod 的入口：注册物品、物品/网络/TaskData 引导、配置 SPEC 装配，以及一段约 320 行的历史默认值迁移链（含 22 个 *_MIGRATED 一次性标记）。

**代码证据**

- `promaid_src_neo/com/maidsmart/ProMaidMod.java`:1
