---
uid: 0fc89045
id: promaid.social.dialogue
parent: promaid.social
name: {zh: 对话与自主决策, en: "Dialogue & Autonomy"}
description:
  zh: >
      LLM 对话周边层：主动对话 7 阶段状态机、回复反馈学习、世界感知气泡、自主切换任务、跨轮工作清单、工作播报、世界探查工具。
  en: >
      The layer around LLM chat: a 7-stage proactive dialogue state machine,
      reply-feedback learning, perception bubbles, autonomous task switching, a
      cross-turn work list, work reporting and world-probe tools.
source:
  - {path: promaid_src_neo/com/maidsmart/dialogue/ProactiveDialogueManager.java, line: 1, end_line: 605}
  - {path: promaid_src_neo/com/maidsmart/dialogue/ProactiveStage.java, line: 1, end_line: 53}
  - {path: promaid_src_neo/com/maidsmart/dialogue/AutonomousTaskManager.java, line: 1, end_line: 119}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 43a89e09822085dc4582af0902ec8e6876e14d9c23c898dec6bb8268a4b6fdfd
state: active
tags: [dialogue, ai]
deps:
  - {kind: call, to: promaid.social.memory, label: {zh: 记忆, en: Memory}}
  - {kind: call, to: promaid.social.affect, label: {zh: 情绪, en: Affect}}
---

## 对话与自主决策 · Dialogue & Autonomy

LLM 对话周边层：主动对话 7 阶段状态机、回复反馈学习、世界感知气泡、自主切换任务、跨轮工作清单、工作播报、世界探查工具。

**代码证据**

- `promaid_src_neo/com/maidsmart/dialogue/ProactiveDialogueManager.java`:1
- `promaid_src_neo/com/maidsmart/dialogue/ProactiveStage.java`:1
- `promaid_src_neo/com/maidsmart/dialogue/AutonomousTaskManager.java`:1
