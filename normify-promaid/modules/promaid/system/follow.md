---
uid: 60bc1c47
id: promaid.system.follow
parent: promaid.system
name: {zh: 区块加载与跨维跟随, en: "Chunk Loading & Cross-Dimension Follow"}
description:
  zh: >
      每 5 秒给每个在职女仆所在区块挂实体 tick
      块共享引用计数与票龄守卫；跨维度用原版传送跟随；推进未加载女仆的召唤/召回队列；为排班女仆持久化票据；并自愈「服务端活着、客户端幽灵」的可见性。1666
      行，仍把票据/跟随/队列/可见性混在一起。
  en: >
      Every 5 seconds keeps an entity-ticking ticket on each active maid's chunk,
      with per-chunk refcounted sharing and a ticket-age guard; follows across
      dimensions via vanilla teleport; advances the summon/recall queue for
      unloaded maids; persists tickets for scheduled maids; and self-heals the
      'alive server-side, ghost client-side' state. 1666 lines, still mixing
      tickets, follow, queue and visibility in one class.
source:
  - {path: promaid_src_neo/com/maidsmart/follow/MaidChunkLoadManager.java, line: 1, end_line: 2090}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 148e8c3588c01496253b3738a7942834097e32b0b10e655d26fff7ab4b2c7b3b
state: active
tags: [system]
apis:
  - protocol: rpc
    path: MaidChunkLoadManager.tick(...)
    description: {zh: 每 5 秒续票。, en: Refreshes tickets every 5s.}
  - protocol: rpc
    path: MaidChunkLoadManager.followIfCrossDimension(...)
    description: {zh: 跨维跟随。, en: Cross-dimension follow.}
deps:
  - {kind: call, to: promaid.system.visibility, label: {zh: 可见性, en: Visibility}}
---

## 区块加载与跨维跟随 · Chunk Loading & Cross-Dimension Follow

每 5 秒给每个在职女仆所在区块挂实体 tick 级别的票，按区块共享引用计数与票龄守卫；跨维度用原版传送跟随；推进未加载女仆的召唤/召回队列；为排班女仆持久化票据；并自愈「服务端活着、客户端幽灵」的可见性。1666 行，仍把票据/跟随/队列/可见性混在一起。

**代码证据**

- `promaid_src_neo/com/maidsmart/follow/MaidChunkLoadManager.java`:1
