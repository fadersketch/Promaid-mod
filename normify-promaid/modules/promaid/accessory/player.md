---
uid: 810b1042
id: promaid.accessory.player
parent: promaid.accessory
name: {zh: 玩家侧佩戴, en: Player-Side Wear}
description:
  zh: >
      把「大运」挂进玩家 Curios 饰品栏（全反射识别，没装 Curios 就静默不生效）：随玩家服务端 tick
      调开路与撞击，戴着滑翔即生效，不需要空袭模式。对外提供「这叠是不是大运」的全反射判据，供女仆侧与穿墙锁敌共用。
  en: >
      Slots Dayun into a player's Curios slot (found by reflection; silently
      inactive when Curios is absent) and drives the breaker and the ram from the
      player's server tick - gliding is enough, no air raid needed. It also
      exposes the reflection-based is-this-stack-a-Dayun predicate shared by the
      maid side and the wall-phase module.
source:
  - {path: promaid_src_neo/com/maidsmart/accessory/DaYunPlayerHook.java, line: 1, end_line: 110}
revision: 511124af04e5d93bda3ca416f6c81356ee49ca0c
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 2c889862382cdfdbcad4e93594f78e1ebec8ec199eaee484b1822a5f9985f1d4
state: active
tags: [accessory]
apis:
  - protocol: event
    path: DaYunPlayerHook.tick(MinecraftServer)
    description: {zh: 玩家服务端每 tick：开路 + 撞击 + BGM。, en: "Per player server tick: break + ram + BGM."}
  - protocol: rpc
    path: DaYunPlayerHook.hasDaYun(ServerPlayer)
    description: {zh: 这名玩家戴着没有（全反射扫 Curios）。, en: Does this player wear one (reflection over Curios).}
  - protocol: rpc
    path: DaYunPlayerHook.isDaYun(ItemStack)
    description: {zh: 这叠是不是「大运」——跨模块共用的判据。, en: Is this stack a Dayun - the predicate shared across modules.}
deps:
  - {kind: call, to: promaid.accessory.breaker, label: {zh: 开路, en: Clearing}}
  - {kind: call, to: promaid.accessory.smash, label: {zh: 撞击, en: Ramming}}
  - {kind: call, to: promaid.accessory.bgm, label: {zh: 飞行 BGM, en: Flight BGM}}
---

## 玩家侧佩戴 · Player-Side Wear

把「大运」挂进玩家 Curios 饰品栏（全反射识别，没装 Curios 就静默不生效）：随玩家服务端 tick 调开路与撞击，戴着滑翔即生效，不需要空袭模式。对外提供「这叠是不是大运」的全反射判据，供女仆侧与穿墙锁敌共用。

**代码证据**

- `promaid_src_neo/com/maidsmart/accessory/DaYunPlayerHook.java`:1
