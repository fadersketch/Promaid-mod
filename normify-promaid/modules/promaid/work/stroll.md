---
uid: ffff2120
id: promaid.work.stroll
parent: promaid.work
name: {zh: 空闲散步, en: Idle Stroll}
description:
  zh: >
      核心行为优先级 50：给空闲女仆一个散步目标（TLM 原生散步概率约 8e-6/tick，基本不动）；遵守工作/战斗/禁足边界。
  en: >
      Core behavior at priority 50: gives idle maids a stroll target, since TLM's
      native stroll chance is about 8e-6/tick (i.e. she never moves). Respects
      work/combat/restriction bounds.
source:
  - {path: promaid_src_neo/com/maidsmart/task/MaidStrollBehavior.java, line: 1, end_line: 234}
revision: 511124af04e5d93bda3ca416f6c81356ee49ca0c
updated_at: "2026-10-01T00:00:00Z"
fingerprint: f4d2933c4f5c4bc124c9e0685d690c28c59675622134cf0e6a165fb595d4316e
state: active
tags: [work, idle]
---

## 空闲散步 · Idle Stroll

核心行为优先级 50：给空闲女仆一个散步目标（TLM 原生散步概率约 8e-6/tick，基本不动）；遵守工作/战斗/禁足边界。

**代码证据**

- `promaid_src_neo/com/maidsmart/task/MaidStrollBehavior.java`:1
