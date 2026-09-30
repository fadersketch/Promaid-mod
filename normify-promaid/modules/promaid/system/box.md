---
uid: 282186c4
id: promaid.system.box
parent: promaid.system
name: {zh: 压缩盒, en: Compression Box}
description:
  zh: >
      5 格、每格最多 11
      万多的收纳道具；右键箱子式界面鼠标取放；放进女仆背包即其背包延伸；禁入压缩盒与附魔类（不可堆叠带组件）防消失。压缩盒自测是住在主源码里的 702
      行假玩家校验。
  en: >
      A 5-slot container holding over a hundred thousand items per slot, with a
      chest-like click UI; placing it in a maid's backpack extends her inventory.
      Compression boxes and enchanted items are barred (unstackable with
      components) to prevent loss. The box self-test is a 702-line fake-player
      check living in main source.
source:
  - {path: promaid_src_neo/com/maidsmart/box/CompressionBoxService.java, line: 1, end_line: 544}
  - {path: promaid_src_neo/com/maidsmart/box/CompressionBoxData.java, line: 1, end_line: 339}
  - {path: promaid_src_neo/com/maidsmart/box/CompressionBoxMaidInv.java, line: 1, end_line: 315}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 3e545f6bb86aa9b331e1db81bd03d5310bceded46c9a2498fa94dda6663b0e7f
state: active
tags: [system, item]
apis:
  - protocol: dataflow
    path: CompressionBoxData NBT
    description: {zh: 自定义压缩盒 NBT。, en: Custom compression-box NBT.}
---

## 压缩盒 · Compression Box

5 格、每格最多 11 万多的收纳道具；右键箱子式界面鼠标取放；放进女仆背包即其背包延伸；禁入压缩盒与附魔类（不可堆叠带组件）防消失。压缩盒自测是住在主源码里的 702 行假玩家校验。

**代码证据**

- `promaid_src_neo/com/maidsmart/box/CompressionBoxService.java`:1
- `promaid_src_neo/com/maidsmart/box/CompressionBoxData.java`:1
- `promaid_src_neo/com/maidsmart/box/CompressionBoxMaidInv.java`:1
