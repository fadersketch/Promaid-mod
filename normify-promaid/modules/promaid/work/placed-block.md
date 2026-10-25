---
uid: fc406675
id: promaid.work.placed-block
parent: promaid.work
name: {zh: 搭方块统一回收, en: Placed-Block Reclaim}
description:
  zh: >
      把每个放置的脚手架方块与其放置者 UUID
      /搭路/自保。实测一千〇六起同族带金色描边：登记/移除经网络层同步到客户端渲染（PlacedMarkClient），空手潜行右键可立刻强制回收成掉落物。
  en: >
      Binds every scaffold block to its placing maid's UUID with lifetime expiry,
      soul-spell pause, cross-dimension return to backpack and crash-safe startup
      cleanup. Four owners share it: mining, woodcutting, bridging and
      self-preservation. Since R999 the same family also drives the client-side
      gold outline (PlacedMarkClient) synced from the server registry, plus
      empty-hand sneak right-click force reclaim into dropped items.
source:
  - {path: promaid_src_neo/com/maidsmart/task/PlacedBlockTracker.java, line: 1, end_line: 460}
  - {path: promaid_src_neo/com/maidsmart/client/PlacedMarkClient.java, line: 1}
revision: f33c45a1ff17ca592b7a3d4ee76d313eb49dac00
updated_at: "2026-10-01T00:00:00Z"
fingerprint: c5533b11ac4c446cfebc3b35f582ae69b172ce03fd1b66d27c65677ebce760af
state: active
tags: [work, shared]
apis:
  - protocol: rpc
    path: PlacedBlockTracker.place(...)
    description: {zh: 登记放置方块与主人 UUID。, en: Registers a placed block and its owner UUID.}
  - protocol: rpc
    path: PlacedBlockTracker.expirePlaced(...)
    description: {zh: 到期回收。, en: Reclaims on expiry.}
---

## 搭方块统一回收 · Placed-Block Reclaim

把每个放置的脚手架方块与其放置者 UUID 绑定；含寿命到期、回魂暂停、跨维度归还背包、崩溃后安全清理。四个主人共用：挖矿/伐木/搭路/自保。实测一千〇六起同族带金色描边：登记/移除经网络层同步到客户端渲染（PlacedMarkClient），空手潜行右键可立刻强制回收成掉落物。

**代码证据**

- `promaid_src_neo/com/maidsmart/task/PlacedBlockTracker.java`:1
- `promaid_src_neo/com/maidsmart/client/PlacedMarkClient.java`:1
