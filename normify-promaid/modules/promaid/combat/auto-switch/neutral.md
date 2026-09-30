---
uid: 99a235b5
id: promaid.combat.auto-switch.neutral
parent: promaid.combat.auto-switch
name: {zh: 中立威胁驱动, en: Neutral Threat Driver}
description:
  zh: >
      把发狂的狼、被模组魔改成中立生物的目标也纳入「威胁」。
  en: >
      Also counts enraged wolves and mod-mutated neutral mobs as threats.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/NeutralThreatDriver.java, line: 1, end_line: 590}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: a2cbc9bcbf988f5758a81b5d3f7a9c23bd200a19a211fced654dfd5dc8225d33
state: active
tags: [combat]
deps:
  - {kind: call, to: promaid.combat.auto-switch, label: {zh: 参战开关, en: Combat switch}}
---

## 中立威胁驱动 · Neutral Threat Driver

把发狂的狼、被模组魔改成中立生物的目标也纳入「威胁」。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/NeutralThreatDriver.java`:1
