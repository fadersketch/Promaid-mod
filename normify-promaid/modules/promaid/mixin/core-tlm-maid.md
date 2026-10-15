---
uid: 00ec5164
id: promaid.mixin.core-tlm-maid
parent: promaid.mixin
name: {zh: TLM 女仆本体注入, en: TLM EntityMaid Core Patches}
description:
  zh: >
      直接给 EntityMaid 打补丁：站桩锁位、传送豁免、扫击伤害、游泳姿态、任务/日程守卫、玩家伤害策略、压缩盒背包延伸、床铺互通。
  en: >
      Patches EntityMaid directly: stand-still locking, teleport exemption, sweep
      damage, swim pose, task/schedule guards, player-damage policy,
      compression-box inventory extension and bed interop.
source:
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidStationaryMixin.java, line: 1, end_line: 44}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidTeleportPreserveMixin.java, line: 1, end_line: 110}
  - {path: promaid_src_neo/com/maidsmart/mixin/WorkRangeExpandMixin.java, line: 1, end_line: 27}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidCompressionBoxMixin.java, line: 1, end_line: 161}
revision: f33c45a1ff17ca592b7a3d4ee76d313eb49dac00
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 632f4cca034ae0d8ab83619a883c19f407b183b90656e4a2984d3aba19dde15b
state: active
tags: [mixin]
apis:
  - protocol: rpc
    path: EntityMaid.travel(...)
    description: {zh: 注入：站桩期接管移动。, en: "Injection: takes over movement while standing."}
  - protocol: rpc
    path: EntityMaid.teleportToOwner()
    description: {zh: 注入：停放/空袭期豁免传送。, en: "Injection: exempts teleport while parked/airborne."}
deps:
  - {kind: call, to: promaid.work.tags, label: {zh: 站桩标记, en: Work-still tags}}
  - {kind: call, to: promaid.core.safety, label: {zh: 无主降级, en: Ownerless downgrade}}
---

## TLM 女仆本体注入 · TLM EntityMaid Core Patches

直接给 EntityMaid 打补丁：站桩锁位、传送豁免、扫击伤害、游泳姿态、任务/日程守卫、玩家伤害策略、压缩盒背包延伸、床铺互通。

**代码证据**

- `promaid_src_neo/com/maidsmart/mixin/MaidStationaryMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidTeleportPreserveMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/WorkRangeExpandMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidCompressionBoxMixin.java`:1
