---
uid: 0a493786
id: promaid.social.action
parent: promaid.social
name: {zh: 情绪动作与物品交互, en: "Emotional Actions & Item Use"}
description:
  zh: >
      主动对话触发时的游戏内动作（走到或看向主人、心形粒子、递食）；喂水软兼容 Thirst；按「剩余次数」区分的物品用途键。
  en: >
      In-world actions triggered by proactive dialogue (walk to or look at the
      owner, heart particles, offer food); soft Thirst compat for water; and
      per-remaining-use item-purpose keys.
source:
  - {path: promaid_src_neo/com/maidsmart/action/EmotionalActionExecutor.java, line: 1, end_line: 300}
  - {path: promaid_src_neo/com/maidsmart/action/ThirstCompat.java, line: 1, end_line: 248}
  - {path: promaid_src_neo/com/maidsmart/action/ItemUses.java, line: 1, end_line: 186}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: ac162abe8c73d3980624ae053488bc44096e9acfd62a211082d71f1635cea03a
state: active
tags: [social, compat]
---

## 情绪动作与物品交互 · Emotional Actions & Item Use

主动对话触发时的游戏内动作（走到或看向主人、心形粒子、递食）；喂水软兼容 Thirst；按「剩余次数」区分的物品用途键。

**代码证据**

- `promaid_src_neo/com/maidsmart/action/EmotionalActionExecutor.java`:1
- `promaid_src_neo/com/maidsmart/action/ThirstCompat.java`:1
- `promaid_src_neo/com/maidsmart/action/ItemUses.java`:1
