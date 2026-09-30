---
uid: ffff2120
id: promaid.work.stroll
parent: promaid.work
name: {zh: 空闲散步, en: Idle Stroll}
description:
  zh: >
      核心行为优先级 50：给空闲女仆一个散步目标（TLM 原生散步概率约
      8e-6/tick，基本不动）；遵守工作/战斗/禁足边界。MaidStrollCheck 仅为一条速度投诉的调试命令而存在。
  en: >
      Core behavior at priority 50: gives idle maids a stroll target, since TLM's
      native stroll chance is about 8e-6/tick (i.e. she never moves). Respects
      work/combat/restriction bounds. MaidStrollCheck exists purely to back a
      debug command for a speed complaint.
source:
  - {path: promaid_src_neo/com/maidsmart/task/MaidStrollBehavior.java, line: 1, end_line: 240}
  - {path: promaid_src_neo/com/maidsmart/task/MaidStrollCheck.java, line: 1, end_line: 413}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 63e4166bb8ad36f31373e29b22770ccf7ccbca3a2217fc224c776c2cf484be5e
state: active
tags: [work, idle]
---

## 空闲散步 · Idle Stroll

核心行为优先级 50：给空闲女仆一个散步目标（TLM 原生散步概率约 8e-6/tick，基本不动）；遵守工作/战斗/禁足边界。MaidStrollCheck 仅为一条速度投诉的调试命令而存在。

**代码证据**

- `promaid_src_neo/com/maidsmart/task/MaidStrollBehavior.java`:1
- `promaid_src_neo/com/maidsmart/task/MaidStrollCheck.java`:1
