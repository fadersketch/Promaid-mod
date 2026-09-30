---
uid: 432261b7
id: promaid.system.schedule.switch
parent: promaid.system.schedule
name: {zh: 任务切换引擎, en: Task Switch Engine}
description:
  zh: >
      唯一的任务/日程写入出口，带分层闸门与守卫；任务可用性判定做硬性开关检查。
  en: >
      The single exit for writing task/schedule changes, with layered gates and
      guards; task availability applies the hard enable check.
source:
  - {path: promaid_src_neo/com/maidsmart/schedule/ScheduleSwitchEngine.java, line: 1, end_line: 176}
  - {path: promaid_src_neo/com/maidsmart/schedule/ScheduleTaskAvailability.java, line: 1, end_line: 296}
  - {path: promaid_src_neo/com/maidsmart/schedule/ScheduleSwitchGuard.java, line: 1, end_line: 73}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 29e61822d60dd1bf10b4c389685360f22ab3e3471cc8c9c3b35bf3ee10b27d2f
state: active
tags: [system]
---

## 任务切换引擎 · Task Switch Engine

唯一的任务/日程写入出口，带分层闸门与守卫；任务可用性判定做硬性开关检查。

**代码证据**

- `promaid_src_neo/com/maidsmart/schedule/ScheduleSwitchEngine.java`:1
- `promaid_src_neo/com/maidsmart/schedule/ScheduleTaskAvailability.java`:1
- `promaid_src_neo/com/maidsmart/schedule/ScheduleSwitchGuard.java`:1
