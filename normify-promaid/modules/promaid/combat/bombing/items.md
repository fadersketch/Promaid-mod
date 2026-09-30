---
uid: 5045f91d
id: promaid.combat.bombing.items
parent: promaid.combat.bombing
name: {zh: 炸弹选材与判据, en: Bomb Material Selection}
description:
  zh: >
      按背包材料决定放哪一段：注册名里带 tnt 的、方块继承原版 TntBlock 的模组 TNT 都认；认出来的是哪一件就放它自己那一枚。
  en: >
      Picks which bomb to place from her inventory: any modded TNT whose registry
      name contains 'tnt', or whose block extends vanilla TntBlock, is accepted;
      whichever item is recognised is the one placed.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/BombItems.java, line: 1, end_line: 336}
  - {path: promaid_src_neo/com/maidsmart/combat/BombConfig.java, line: 1, end_line: 151}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 296304d0d53bb2f34253eb38519799f2454fb73c4d0616871ec25ad4d477f9f1
state: active
tags: [combat]
deps:
  - {kind: call, to: promaid.core.config, label: {zh: 配置, en: Config}}
---

## 炸弹选材与判据 · Bomb Material Selection

按背包材料决定放哪一段：注册名里带 tnt 的、方块继承原版 TntBlock 的模组 TNT 都认；认出来的是哪一件就放它自己那一枚。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/BombItems.java`:1
- `promaid_src_neo/com/maidsmart/combat/BombConfig.java`:1
