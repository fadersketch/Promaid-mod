---
uid: 0e60d8cb
id: promaid.accessory.maid
parent: promaid.accessory
name: {zh: 女仆侧佩戴, en: Maid-Side Wear}
description:
  zh: >
      把「大运」挂进女仆的 TLM 饰品栏：注册成 IMaidBauble，随女仆 tick 调开路与撞击，并把她的飞行 BGM
      起停交给音乐模块。只在空袭模式下真正生效（其余时候她在干活，开路没意义）；另对外回答「她是不是在空袭」，给穿墙锁敌与自保复用。
  en: >
      Slots Dayun into a maid's TLM bauble slot: registered as an IMaidBauble it
      drives the breaker and the ram from her tick and hands her flight BGM to the
      music module. It only takes effect in air-raid mode (otherwise she is
      working, where clearing is pointless), and it answers whether she is on an
      air raid for the wall-phase and self-preservation modules.
source:
  - {path: promaid_src_neo/com/maidsmart/accessory/DaYunBauble.java, line: 1, end_line: 102}
revision: 8fbb6334b1c5b0d47a7c9642f74052c0698d3b0f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 09aef49282b00fbd3ba2c34a18db1b83a89e1a60034bb1a25d0d2f0bcef6005f
state: active
tags: [accessory]
apis:
  - protocol: event
    path: "DaYunBauble.onTick(EntityMaid, ItemStack)"
    description: {zh: 女仆每 tick：开路 + 撞击 + BGM。, en: "Per maid tick: break + ram + BGM."}
  - protocol: rpc
    path: DaYunBauble.isAirRaidTask(EntityMaid)
    description: {zh: 她现在是不是空袭任务。, en: Is she on an air-raid task.}
deps:
  - {kind: call, to: promaid.accessory.breaker, label: {zh: 开路, en: Clearing}}
  - {kind: call, to: promaid.accessory.smash, label: {zh: 撞击, en: Ramming}}
  - {kind: call, to: promaid.accessory.bgm, label: {zh: 飞行 BGM, en: Flight BGM}}
---

## 女仆侧佩戴 · Maid-Side Wear

把「大运」挂进女仆的 TLM 饰品栏：注册成 IMaidBauble，随女仆 tick 调开路与撞击，并把她的飞行 BGM 起停交给音乐模块。只在空袭模式下真正生效（其余时候她在干活，开路没意义）；另对外回答「她是不是在空袭」，给穿墙锁敌与自保复用。

**代码证据**

- `promaid_src_neo/com/maidsmart/accessory/DaYunBauble.java`:1
