---
uid: abbd7782
id: promaid.combat.compat
parent: promaid.combat
name: {zh: 第三方战斗兼容, en: Third-Party Combat Compat}
description:
  zh: >
      全反射、零硬依赖地适配枪械（TACZ / 卓越前线）、万法皆通法术附属、拔刀剑、暮色森林孔雀羽扇、Curios
      鞘翅、自推鞘翅。各自重写一遍「反射失败到宽松兜底」模板。
  en: >
      Adapts guns (TACZ / Superb Warfare), the spell addon, SlashBlade, the
      Twilight Forest peacock fan, Curios elytra and self-propelled wings — all by
      reflection with zero hard deps. Each re-implements the same 'reflection
      failed, degrade gracefully' template.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/GunCompat.java, line: 1, end_line: 716}
  - {path: promaid_src_neo/com/maidsmart/combat/MaidSpellCastCompat.java, line: 1, end_line: 727}
  - {path: promaid_src_neo/com/maidsmart/combat/CuriosElytraCompat.java, line: 1, end_line: 288}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: b08c6d492543e0a665e4d4c7aac5fce790946a9c69b8291ae1835e9d2098ac78
state: active
tags: [compat]
---

## 第三方战斗兼容 · Third-Party Combat Compat

全反射、零硬依赖地适配枪械（TACZ / 卓越前线）、万法皆通法术附属、拔刀剑、暮色森林孔雀羽扇、Curios 鞘翅、自推鞘翅。各自重写一遍「反射失败到宽松兜底」模板。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/GunCompat.java`:1
- `promaid_src_neo/com/maidsmart/combat/MaidSpellCastCompat.java`:1
- `promaid_src_neo/com/maidsmart/combat/CuriosElytraCompat.java`:1
