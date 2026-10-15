---
uid: 52594359
id: promaid.work.mine
parent: promaid.work
name: {zh: 挖矿, en: Mining}
description:
  zh: >
      按距离平方加深度惩罚减价值 扫最高价值矿，寻路过去逐块挖（原版破坏公式加工具等级门槛加女仆 1.2
      倍加成），连锁挖整条矿脉；临时垫脚方块事后回收。与伐木共用的静态表、状态表与纯工具已上提到 MaidWorkBehavior。
  en: >
      Scans for the highest-value ore by distance squared plus a depth penalty
      minus value, paths there and mines block by block (vanilla break formula,
      tool-tier gated, 1.2x maid bonus), chain-mining the vein; temporary scaffold
      is reclaimed later. The static tables, state map and pure helpers shared
      with woodcutting were hoisted into MaidWorkBehavior.
source:
  - {path: promaid_src_neo/com/maidsmart/task/MaidMineBehavior.java, line: 1, end_line: 2334}
  - {path: promaid_src_neo/com/maidsmart/task/MaidMineTask.java, line: 1, end_line: 73}
revision: 511124af04e5d93bda3ca416f6c81356ee49ca0c
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 24c7621ec70dcea4955b19e92d258b90250123b87aaf8a0022f3d9bc51498d7b
state: active
tags: [work]
deps:
  - {kind: call, to: promaid.work.base, label: {zh: 共享基类, en: Shared base}}
  - {kind: call, to: promaid.work.placed-block, label: {zh: 方块回收, en: Block reclaim}}
  - {kind: call, to: promaid.work.tool-equip, label: {zh: 自动换工具, en: Auto tool}}
---

## 挖矿 · Mining

按距离平方加深度惩罚减价值 扫最高价值矿，寻路过去逐块挖（原版破坏公式加工具等级门槛加女仆 1.2 倍加成），连锁挖整条矿脉；临时垫脚方块事后回收。与伐木共用的静态表、状态表与纯工具已上提到 MaidWorkBehavior。

**代码证据**

- `promaid_src_neo/com/maidsmart/task/MaidMineBehavior.java`:1
- `promaid_src_neo/com/maidsmart/task/MaidMineTask.java`:1
