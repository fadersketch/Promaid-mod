---
uid: fc406675
id: promaid.work.placed-block
parent: promaid.work
name: {zh: 搭方块统一回收, en: Placed-Block Reclaim}
description:
  zh: >
      把每个放置的脚手架方块与其放置者 UUID 绑定；含寿命到期、回魂暂停、跨维度归还背包、崩溃后安全清理。四个主人共用：挖矿/伐木/搭路/自保。
  en: >
      Binds every scaffold block to its placing maid's UUID with lifetime expiry,
      soul-spell pause, cross-dimension return to backpack and crash-safe startup
      cleanup. Four owners share it: mining, woodcutting, bridging and
      self-preservation.
source:
  - {path: promaid_src_neo/com/maidsmart/task/PlacedBlockTracker.java, line: 1, end_line: 437}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 9afa3e6e315e2c910f71406982f2a3c4ea1bbb1ff6eb22e78e415fadc27d161e
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

把每个放置的脚手架方块与其放置者 UUID 绑定；含寿命到期、回魂暂停、跨维度归还背包、崩溃后安全清理。四个主人共用：挖矿/伐木/搭路/自保。

**代码证据**

- `promaid_src_neo/com/maidsmart/task/PlacedBlockTracker.java`:1
