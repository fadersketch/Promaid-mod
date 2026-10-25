---
uid: 026b8c87
id: promaid.mixin.dialogue-llm
parent: promaid.mixin
name: {zh: 对话与 LLM 注入, en: "Chat & LLM Patches"}
description:
  zh: >
      语言/回调修复、LLM 调用闸门、system prompt 追加、傀儡模式让位、TTS 音量、气泡限流、本模组系统消息静默。
  en: >
      Language/callback fixes, LLM call gating, system-prompt appending,
      puppet-mode concession, TTS volume, chat-bubble rate limiting and silencing
      this mod's system messages.
source:
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidChatLanguageMixin.java, line: 1, end_line: 88}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidLangCheckMixin.java, line: 1, end_line: 107}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidInternalHistoryMixin.java, line: 1, end_line: 26}
  - {path: promaid_src_neo/com/maidsmart/mixin/ChatBubbleLimitMixin.java, line: 1, end_line: 134}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidChatLlmGateMixin.java, line: 1, end_line: 43}
  - {path: promaid_src_neo/com/maidsmart/mixin/PromaidMessageGateMixin.java, line: 1, end_line: 39}
revision: f33c45a1ff17ca592b7a3d4ee76d313eb49dac00
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 3c6d7e07a6bc63becb6b24d5dad01b9615bfe0bc95b9ee2c268acb8df97cc880
state: active
tags: [mixin]
deps:
  - {kind: call, to: promaid.social.dialogue, label: {zh: 对话层, en: Dialogue layer}}
  - {kind: call, to: promaid.core.prompt, label: {zh: 提示词追加, en: Prompt appender}}
---

## 对话与 LLM 注入 · Chat & LLM Patches

语言/回调修复、LLM 调用闸门、system prompt 追加、傀儡模式让位、TTS 音量、气泡限流、本模组系统消息静默。

**代码证据**

- `promaid_src_neo/com/maidsmart/mixin/MaidChatLanguageMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidLangCheckMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidInternalHistoryMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/ChatBubbleLimitMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidChatLlmGateMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/PromaidMessageGateMixin.java`:1
