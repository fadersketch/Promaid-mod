---
uid: e347a4e5
id: promaid.social.affect
parent: promaid.social
name: {zh: PAD 情绪层, en: PAD Affect Layer}
description:
  zh: >
      独立第四套数值（愉悦/唤醒/支配 加 亲密度/冲突/思念 加 受伤债/修复债），事件驱动回落，作为情感上下文注入对话影响语气；落盘
      affect.json。
  en: >
      A fourth independent numeric layer (pleasure/arousal/dominance plus
      intimacy/conflict/longing and hurt-debt/repair-debt) that decays in response
      to events and is injected as the affect context to tint conversation;
      persisted to affect.json.
source:
  - {path: promaid_src_neo/com/maidsmart/affect/AffectManager.java, line: 1, end_line: 246}
  - {path: promaid_src_neo/com/maidsmart/affect/AffectEventHooks.java, line: 1, end_line: 89}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 2f34d9d94ee62440354057095198e3319be72643743e0539ac3c6ad663878cec
state: active
tags: [affect, ai]
---

## PAD 情绪层 · PAD Affect Layer

独立第四套数值（愉悦/唤醒/支配 加 亲密度/冲突/思念 加 受伤债/修复债），事件驱动回落，作为情感上下文注入对话影响语气；落盘 affect.json。

**代码证据**

- `promaid_src_neo/com/maidsmart/affect/AffectManager.java`:1
- `promaid_src_neo/com/maidsmart/affect/AffectEventHooks.java`:1
