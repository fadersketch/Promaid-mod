---
uid: 46f0c41f
id: promaid.combat.bombing.blast
parent: promaid.combat.bombing
name: {zh: 爆炸与安全口径, en: "Blast & Safety Semantics"}
description:
  zh: >
      「破坏方块」与「伤到主人/友军」默认都关；她自己不会被自己的炸弹炸到或推飞；黑曜石底座默认保留 10 秒再收进背包。
  en: >
      'Break blocks' and 'hurt owner/allies' both default off; she cannot be
      caught or launched by her own bomb; the obsidian base is kept 10 seconds
      before being returned to her inventory.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/BombExplosion.java, line: 1, end_line: 190}
  - {path: promaid_src_neo/com/maidsmart/combat/BombTntTick.java, line: 1, end_line: 193}
  - {path: promaid_src_neo/com/maidsmart/combat/MaidTntBlastGuard.java, line: 1, end_line: 299}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 80906e7587adae2e6ec29e99e9f77c70c2ae6f089c6c55feefa98327cb070553
state: active
tags: [combat, safety]
apis:
  - protocol: rpc
    path: MaidBombing.onAttackLanded(...)
    description: {zh: 打完一记 → 顺带投弹。, en: "After a landed hit -> drop a bomb."}
deps:
  - {kind: call, to: promaid.combat.guards, label: {zh: 友军防护, en: Friendly guard}}
---

## 爆炸与安全口径 · Blast & Safety Semantics

「破坏方块」与「伤到主人/友军」默认都关；她自己不会被自己的炸弹炸到或推飞；黑曜石底座默认保留 10 秒再收进背包。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/BombExplosion.java`:1
- `promaid_src_neo/com/maidsmart/combat/BombTntTick.java`:1
- `promaid_src_neo/com/maidsmart/combat/MaidTntBlastGuard.java`:1
