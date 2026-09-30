---
uid: 20a018e8
id: promaid.work.brew-task
parent: promaid.work
name: {zh: 酿造任务, en: Brewing Task}
description:
  zh: >
      给酿造台补烈焰粉与材料、收成品药水，100 tick 节奏。配方解析见 system.brew。
  en: >
      Refills brewing stands with blaze powder and ingredients and collects
      finished potions, on a 100-tick cadence. Recipe resolution lives in
      system.brew.
source:
  - {path: promaid_src_neo/com/maidsmart/task/MaidBrewBehavior.java, line: 1, end_line: 1189}
  - {path: promaid_src_neo/com/maidsmart/task/MaidBrewTask.java, line: 1, end_line: 63}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: bfe343705cf514846b778235628a705f62b78409a7b13f7ba9dd2c2db9f59736
state: active
tags: [work]
deps:
  - {kind: call, to: promaid.system.brew, label: {zh: 配方, en: Recipes}}
---

## 酿造任务 · Brewing Task

给酿造台补烈焰粉与材料、收成品药水，100 tick 节奏。配方解析见 system.brew。

**代码证据**

- `promaid_src_neo/com/maidsmart/task/MaidBrewBehavior.java`:1
- `promaid_src_neo/com/maidsmart/task/MaidBrewTask.java`:1
