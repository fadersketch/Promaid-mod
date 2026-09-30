---
uid: fd42b488
id: promaid.flight.free
parent: promaid.flight
name: {zh: 仿创造飞行, en: Creative-Like Free Flight}
description:
  zh: >
      女仆携带特定物品/效果/零重力资格时获得悬停与平滑 3D 移动：关重力加每 tick
      一阶速度（按距离算、永不累积），松开时软着陆，平滑偏航与后撤朝向，逐女仆开关（磁盘持久加 S2C 缓存）。刻意做成事件驱动而非大脑行为——注释记载
      core behavior 实例化后其起始判据从不被调用。
  en: >
      When she carries the right item/effect/zero-gravity qualification she gains
      hover and smooth 3D movement: gravity off plus a per-tick first-order
      velocity computed from distance and never accumulated, soft landing on
      release, smoothed yaw and retreat facing, and a per-maid toggle (disk-backed
      plus S2C cached). Deliberately event-driven rather than a brain behavior —
      the comments record that a core behavior instance never got its
      start-condition check called.
source:
  - {path: promaid_src_neo/com/maidsmart/flight/MaidFreeFlightController.java, line: 1, end_line: 1402}
  - {path: promaid_src_neo/com/maidsmart/flight/MaidFreeFlightKit.java, line: 1, end_line: 273}
  - {path: promaid_src_neo/com/maidsmart/flight/MaidFreeFlightFlags.java, line: 1, end_line: 167}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: f39b6805e809811eac9bdcca5feb03d3f32ec06f0e4428ea9e6a954a0df8e6b2
state: active
tags: [flight]
---

## 仿创造飞行 · Creative-Like Free Flight

女仆携带特定物品/效果/零重力资格时获得悬停与平滑 3D 移动：关重力加每 tick 一阶速度（按距离算、永不累积），松开时软着陆，平滑偏航与后撤朝向，逐女仆开关（磁盘持久加 S2C 缓存）。刻意做成事件驱动而非大脑行为——注释记载 core behavior 实例化后其起始判据从不被调用。

**代码证据**

- `promaid_src_neo/com/maidsmart/flight/MaidFreeFlightController.java`:1
- `promaid_src_neo/com/maidsmart/flight/MaidFreeFlightKit.java`:1
- `promaid_src_neo/com/maidsmart/flight/MaidFreeFlightFlags.java`:1
