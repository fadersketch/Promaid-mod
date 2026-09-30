---
uid: 026b8c87
id: promaid.mixin.dialogue-llm
parent: promaid.mixin
name: {zh: 对话与 LLM 注入, en: "Chat & LLM Patches"}
description:
  zh: >
      语言/回调修复、LLM 调用闸门、system prompt 追加、傀儡模式让位、TTS 音量、气泡限流。
  en: >
      Language/callback fixes, LLM call gating, system-prompt appending,
      puppet-mode concession, TTS volume and chat-bubble rate limiting.
source:
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidChatLanguageMixin.java, line: 1, end_line: 162}
  - {path: promaid_src_neo/com/maidsmart/mixin/ChatBubbleLimitMixin.java, line: 1, end_line: 134}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidChatLlmGateMixin.java, line: 1, end_line: 43}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: eaabbd2b5b2905321b299ecea49df7998e515962515b44280e04b7b572c927bf
state: active
tags: [mixin]
deps:
  - {kind: call, to: promaid.social.dialogue, label: {zh: 对话层, en: Dialogue layer}}
  - {kind: call, to: promaid.core.prompt, label: {zh: 提示词追加, en: Prompt appender}}
---

## 对话与 LLM 注入 · Chat & LLM Patches

语言/回调修复、LLM 调用闸门、system prompt 追加、傀儡模式让位、TTS 音量、气泡限流。

**代码证据**

- `promaid_src_neo/com/maidsmart/mixin/MaidChatLanguageMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/ChatBubbleLimitMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidChatLlmGateMixin.java`:1
