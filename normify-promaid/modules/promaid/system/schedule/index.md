---
uid: 10eb05e7
id: promaid.system.schedule
parent: promaid.system
name: {zh: 排班表, en: Schedule System}
description:
  zh: >
      排班表物品右键打开界面：选女仆、选班次（早班/晚班/全天）、6 个任务槽排一天；底层存时段段表，按游戏挂钟每分钟扫描自动切任务。启用排班时会强制开
      home 模式并自动锚定家位置（治「呆立」）。
  en: >
      The schedule book item opens a UI: pick maids, pick a shift (day/night/all),
      fill 6 task slots across a day. Underneath it stores segments and scans the
      world clock every minute to switch tasks. Enabling a schedule forces home
      mode on and auto-anchors the home position (fixes the 'stands frozen' bug).
source:
  - {path: promaid_src_neo/com/maidsmart/schedule/ScheduleManager.java, line: 1, end_line: 387}
  - {path: promaid_src_neo/com/maidsmart/schedule/ScheduleData.java, line: 1, end_line: 275}
  - {path: promaid_src_neo/com/maidsmart/schedule/ScheduleBookScreen.java, line: 1, end_line: 1167}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: d7935fff2efcbf71a43015f4e635ba0c02e6e4de87fe56675389c39ddfc1fc70
state: active
tags: [system, ui]
deps:
  - {kind: call, to: promaid.combat.auto-switch, label: {zh: 优先级让位, en: Priority concession}}
  - {kind: call, to: promaid.combat.self-preservation, label: {zh: 优先级让位, en: Priority concession}}
---

## 排班表 · Schedule System

排班表物品右键打开界面：选女仆、选班次（早班/晚班/全天）、6 个任务槽排一天；底层存时段段表，按游戏挂钟每分钟扫描自动切任务。启用排班时会强制开 home 模式并自动锚定家位置（治「呆立」）。

**代码证据**

- `promaid_src_neo/com/maidsmart/schedule/ScheduleManager.java`:1
- `promaid_src_neo/com/maidsmart/schedule/ScheduleData.java`:1
- `promaid_src_neo/com/maidsmart/schedule/ScheduleBookScreen.java`:1
