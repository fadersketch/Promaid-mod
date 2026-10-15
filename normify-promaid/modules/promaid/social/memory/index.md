---
uid: 35f6c36d
id: promaid.social.memory
parent: promaid.social
name: {zh: 长期记忆, en: Long-term Memory}
description:
  zh: >
      每女仆的长期记忆库：事件源注入加后台 LLM 提取加五表 jsonl
      落盘（段落/实体/关系/片段/画像）加多级日记式索引加睡醒或登出自动归档；对话时只注入短投影。
  en: >
      A per-maid long-term memory store: event-source injection, background LLM
      extraction, five jsonl tables
      (paragraphs/entities/relations/episodes/profiles), multi-level diary-style
      indexing and auto-archival on wake or logout — with only a short projection
      injected into chat.
source:
  - {path: promaid_src_neo/com/maidsmart/memory/AiMemoryStore.java, line: 1, end_line: 998}
  - {path: promaid_src_neo/com/maidsmart/memory/AiMemoryExtractor.java, line: 1, end_line: 636}
  - {path: promaid_src_neo/com/maidsmart/memory/AiMemoryArchiver.java, line: 1, end_line: 597}
revision: f33c45a1ff17ca592b7a3d4ee76d313eb49dac00
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 1ec5be28ac93e34b369e8f55a2f85aa3ec2606e8d8815ab4385e132e198dea64
state: active
tags: [memory, ai]
---

## 长期记忆 · Long-term Memory

每女仆的长期记忆库：事件源注入加后台 LLM 提取加五表 jsonl 落盘（段落/实体/关系/片段/画像）加多级日记式索引加睡醒或登出自动归档；对话时只注入短投影。

**代码证据**

- `promaid_src_neo/com/maidsmart/memory/AiMemoryStore.java`:1
- `promaid_src_neo/com/maidsmart/memory/AiMemoryExtractor.java`:1
- `promaid_src_neo/com/maidsmart/memory/AiMemoryArchiver.java`:1
