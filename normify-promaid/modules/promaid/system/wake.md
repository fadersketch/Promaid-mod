---
uid: 78db93cf
id: promaid.system.wake
parent: promaid.system
name: {zh: 叫醒与找床断路, en: "Waking & Sleep Block"}
description:
  zh: >
      拿着本模组物品右击睡着的女仆即可叫醒：停止睡眠、清掉寻床目标、播一条抱怨气泡与语音，并让两条找床链路（TLM 的女仆床任务与本模组的原版床行为）60
      秒内不启动；收放魂符会清空这段断路。
  en: >
      Right-clicking a sleeping maid with any this-mod item wakes her: stops
      sleeping, clears walk targets, plays a complaint bubble and voice, and keeps
      both bed-seeking chains (TLM's maid-bed task and our vanilla-bed behavior)
      from starting for 60 seconds; the soul spell clears the block.
source:
  - {path: promaid_src_neo/com/maidsmart/task/MaidWakeHandler.java, line: 1, end_line: 110}
  - {path: promaid_src_neo/com/maidsmart/task/MaidBedInteropBehavior.java, line: 1, end_line: 164}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidBedWakeBlockMixin.java, line: 1, end_line: 32}
revision: f33c45a1ff17ca592b7a3d4ee76d313eb49dac00
updated_at: "2026-10-01T00:00:00Z"
fingerprint: f0ef8a3d4ac4c18eea989b9f2f85e070bf82696cee63589a2fb756c5d9473ae4
state: active
tags: [system]
deps:
  - {kind: call, to: promaid.social.voice, label: {zh: 抱怨语音, en: Complaint voice}}
---

## 叫醒与找床断路 · Waking & Sleep Block

拿着本模组物品右击睡着的女仆即可叫醒：停止睡眠、清掉寻床目标、播一条抱怨气泡与语音，并让两条找床链路（TLM 的女仆床任务与本模组的原版床行为）60 秒内不启动；收放魂符会清空这段断路。

**代码证据**

- `promaid_src_neo/com/maidsmart/task/MaidWakeHandler.java`:1
- `promaid_src_neo/com/maidsmart/task/MaidBedInteropBehavior.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidBedWakeBlockMixin.java`:1
