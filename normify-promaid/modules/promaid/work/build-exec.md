---
uid: b2c42685
id: promaid.work.build-exec
parent: promaid.work
name: {zh: 建造执行, en: Build Execution}
description:
  zh: >
      运行时核心：解析、重叠拒绝、障碍告警、已建扫描、材料预检、建或续计划（原点取玩家脚下、不需要女仆），以及女仆按光标每 N tick
      放一块（四档速度）。含传送到位、缺料向主人取、TNT 点火守卫、区块重发、缝隙扫描、替代方块、TPS 自适应节奏。
  en: >
      The runtime core: parse, reject overlaps, warn obstacles, scan prebuilt,
      precheck materials, create or resume the plan (origin at the player's feet,
      no maid required), plus a maid placing one block per N ticks (four speed
      tiers). Includes teleport-to-site, fetching missing material from the owner,
      TNT ignition guarding, chunk resend, gap scanning, alternative blocks and
      TPS-adaptive pacing.
source:
  - {path: promaid_src_neo/com/maidsmart/build/MaidBuildBehavior.java, line: 1, end_line: 1951}
  - {path: promaid_src_neo/com/maidsmart/build/BlueprintBuildExecutor.java, line: 1, end_line: 254}
  - {path: promaid_src_neo/com/maidsmart/build/MaidBuildTask.java, line: 1, end_line: 70}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 0b62c10ca254e182fd78ab78ed622002c231825a8a588d9a60ed3bd3fee29f6c
state: active
tags: [build]
apis:
  - protocol: rpc
    path: BlueprintBuildExecutor.execute(...)
    description: {zh: 解析并建立/续接建造计划。, en: Parses and creates or resumes a build plan.}
  - protocol: rpc
    path: MaidBuildBehavior.doPlace(...)
    description: {zh: 放下一块并扣真实材料。, en: Places one block and consumes real material.}
deps:
  - {kind: call, to: promaid.work.build-plan, label: {zh: 计划, en: Plan}}
  - {kind: call, to: promaid.work.blueprint-lib, label: {zh: 蓝图库, en: Blueprint lib}}
---

## 建造执行 · Build Execution

运行时核心：解析、重叠拒绝、障碍告警、已建扫描、材料预检、建或续计划（原点取玩家脚下、不需要女仆），以及女仆按光标每 N tick 放一块（四档速度）。含传送到位、缺料向主人取、TNT 点火守卫、区块重发、缝隙扫描、替代方块、TPS 自适应节奏。

**代码证据**

- `promaid_src_neo/com/maidsmart/build/MaidBuildBehavior.java`:1
- `promaid_src_neo/com/maidsmart/build/BlueprintBuildExecutor.java`:1
- `promaid_src_neo/com/maidsmart/build/MaidBuildTask.java`:1
