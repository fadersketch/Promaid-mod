---
uid: 747afe21
id: promaid.combat.broom
parent: promaid.combat
name: {zh: 扫帚模式, en: Broom Mode}
description:
  zh: >
      取出 TLM 扫帚实体骑上去飞行、悬停、接敌爬升盘旋，开火链路与远程空袭完全同款；含牵引绳回传与套件校验。只有玩家手动指派才会进。
  en: >
      She mounts a TLM broom entity, flies, hovers and orbits to engage — the
      firing chain is identical to ranged air raid. Includes leash recall and kit
      validation. Only entered when the player assigns it.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/MaidBroomDrive.java, line: 1, end_line: 2571}
  - {path: promaid_src_neo/com/maidsmart/combat/MaidBroomBehavior.java, line: 1, end_line: 788}
  - {path: promaid_src_neo/com/maidsmart/combat/MaidBroomKit.java, line: 1, end_line: 370}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 966856eb0c1be532bb13b6b24a253ac3a407def6b60357a6e4f1b53c27a3bb09
state: active
tags: [combat, flight]
apis:
  - protocol: rpc
    path: MaidBroomDrive.takeThrust(...)
    description: {zh: 每拍推进量（被扫帚实体 travel 注入读取）。, en: "Per-tick thrust, read by the broom entity travel injection."}
  - protocol: rpc
    path: MaidBroomKit.isBroomTask(...)
    description: {zh: 任务判定契约（多处方引用）。, en: Task predicate contract (referenced from several mixins).}
deps:
  - {kind: call, to: promaid.combat.flight-raid, label: {zh: 开火链路, en: Firing chain}}
  - {kind: call, to: promaid.combat.ride, label: {zh: 骑乘工具, en: Ride kit}}
---

## 扫帚模式 · Broom Mode

取出 TLM 扫帚实体骑上去飞行、悬停、接敌爬升盘旋，开火链路与远程空袭完全同款；含牵引绳回传与套件校验。只有玩家手动指派才会进。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/MaidBroomDrive.java`:1
- `promaid_src_neo/com/maidsmart/combat/MaidBroomBehavior.java`:1
- `promaid_src_neo/com/maidsmart/combat/MaidBroomKit.java`:1
