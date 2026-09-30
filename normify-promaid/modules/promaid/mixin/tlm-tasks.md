---
uid: cc60d726
id: promaid.mixin.tlm-tasks
parent: promaid.mixin
name: {zh: TLM 任务行为注入, en: TLM Task Behavior Patches}
description:
  zh: >
      对 TLM 内置任务类做闸门/限频/奖励修正：吃饭、偷吃、恐慌、跟随、挤奶、剪毛、采蜜、钓鱼落座、盾牌、近战/激流、拾取优先级、收割/耕地、大脑节流。
  en: >
      Gates, rate-limits and reward-tunes TLM's built-in task classes: meal,
      steal-edible, panic, follow, milking, shearing, honey, fishing-seat, shield,
      melee/riptide, pickup priority, harvest/till and brain throttling.
source:
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidFollowOwnerTickMixin.java, line: 1, end_line: 88}
  - {path: promaid_src_neo/com/maidsmart/mixin/FarmSweepMixin.java, line: 1, end_line: 285}
  - {path: promaid_src_neo/com/maidsmart/mixin/NativeTaskSmoothMixin.java, line: 1, end_line: 132}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: d59f6d2c64375e83365e60498cf7e27910bf2c58d6661e5435fb2e367783d30a
state: active
tags: [mixin]
deps:
  - {kind: call, to: promaid.system.schedule, label: {zh: 日程守卫, en: Schedule guard}}
  - {kind: call, to: promaid.work.tags, label: {zh: 站桩标记, en: Work-still tags}}
---

## TLM 任务行为注入 · TLM Task Behavior Patches

对 TLM 内置任务类做闸门/限频/奖励修正：吃饭、偷吃、恐慌、跟随、挤奶、剪毛、采蜜、钓鱼落座、盾牌、近战/激流、拾取优先级、收割/耕地、大脑节流。

**代码证据**

- `promaid_src_neo/com/maidsmart/mixin/MaidFollowOwnerTickMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/FarmSweepMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/NativeTaskSmoothMixin.java`:1
