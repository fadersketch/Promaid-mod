---
uid: e6503375
id: promaid.accessory.bgm
parent: promaid.accessory
name: {zh: 飞行 BGM, en: Flight BGM}
description:
  zh: >
      佩戴者飞起来时给他/她（和 200 格内听得到的玩家）放一首 BGM，落地或摘饰品就停。服务端不自己播声音——只把「谁的 BGM 开始 /
      停了」发给听得见的人，客户端据此为每个佩戴者各建一条声音实例，否则两人同曲时停一个会把另一条一起掐掉。为不被每 tick
      的调用刷爆做边沿触发；又为让收翅猛击按下滑翔位那几拍不把音乐拆掉重播，停止走 60 刻宽限期。
  en: >
      Plays one BGM for a gliding wearer (and for players within 200 blocks),
      stopping on landing or when the bauble comes off. The server never plays the
      sound itself - it only tells those who can hear whose BGM started or
      stopped, and the client builds a separate instance per wearer, so stopping
      one of two identical tracks cannot cut the other off. Edge-triggered against
      the per-tick callers; stopping waits out a 60-tick grace window so a
      wing-fold smash does not restart it.
source:
  - {path: promaid_src_neo/com/maidsmart/accessory/DaYunMusic.java, line: 1, end_line: 189}
  - {path: promaid_src_neo/com/maidsmart/accessory/DaYunMusicNetworking.java, line: 1, end_line: 89}
revision: f33c45a1ff17ca592b7a3d4ee76d313eb49dac00
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 3a973f0c941824935522672758393dc4cc1117059badae2f782c63e2256a8995
state: active
tags: [accessory]
apis:
  - protocol: rpc
    path: "DaYunMusic.play(ServerLevel, Entity, int)"
    description: {zh: 某佩戴者的 BGM 开始（边沿触发，状态没变不发包）。, en: A wearer's BGM starts (edge-triggered; no packet when unchanged).}
  - protocol: rpc
    path: "DaYunMusic.stop(ServerLevel, Entity)"
    description: {zh: 停止（走 60 刻宽限期，期间 play 可撤回）。, en: Stop (through a 60-tick grace window that play can retract).}
  - protocol: rpc
    path: "DaYunMusicNetworking.sendMusic(ServerPlayer, int, int, boolean)"
    description: {zh: "S2C：(佩戴者 id, 曲目, 起/停)。", en: "S2C: (wearer id, track, start/stop)."}
---

## 飞行 BGM · Flight BGM

佩戴者飞起来时给他/她（和 200 格内听得到的玩家）放一首 BGM，落地或摘饰品就停。服务端不自己播声音——只把「谁的 BGM 开始 / 停了」发给听得见的人，客户端据此为每个佩戴者各建一条声音实例，否则两人同曲时停一个会把另一条一起掐掉。为不被每 tick 的调用刷爆做边沿触发；又为让收翅猛击按下滑翔位那几拍不把音乐拆掉重播，停止走 60 刻宽限期。

**代码证据**

- `promaid_src_neo/com/maidsmart/accessory/DaYunMusic.java`:1
- `promaid_src_neo/com/maidsmart/accessory/DaYunMusicNetworking.java`:1
