---
uid: 97523d32
id: promaid.social.memory.index
parent: promaid.social.memory
name: {zh: 记忆索引与检索, en: "Memory Index & Recall"}
description:
  zh: >
      日记式索引、混合召回与 RRF 融合、跳过表（移植自 Sphantosis 的
      cognitive/composite/skiplist_index.py）。
  en: >
      Diary-style index, hybrid recall with RRF fusion, and a skip list (ported
      from Sphantosis's cognitive/composite/skiplist_index.py).
source:
  - {path: promaid_src_neo/com/maidsmart/memory/AiMemoryIndexStore.java, line: 1, end_line: 171}
  - {path: promaid_src_neo/com/maidsmart/memory/AiMemorySearch.java, line: 1, end_line: 221}
  - {path: promaid_src_neo/com/maidsmart/memory/AiMemorySkipList.java, line: 1, end_line: 126}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: f1277caaed79a573c75b19405f430a5dfd1fe7eb06d9fabf169c1e348d98ad20
state: active
tags: [memory]
apis:
  - protocol: file
    path: "<world>/promaid_memory/<uuid>/paragraphs.jsonl"
    description: {zh: 段落记忆表。, en: Paragraph memory table.}
  - protocol: file
    path: "<world>/promaid_memory/<uuid>/relations.jsonl"
    description: {zh: 关系表。, en: Relations table.}
  - protocol: rpc
    path: AiMemoryStore.append(...)
    description: {zh: 写入一条记忆。, en: Appends one memory entry.}
---

## 记忆索引与检索 · Memory Index & Recall

日记式索引、混合召回与 RRF 融合、跳过表（移植自 Sphantosis 的 cognitive/composite/skiplist_index.py）。

**代码证据**

- `promaid_src_neo/com/maidsmart/memory/AiMemoryIndexStore.java`:1
- `promaid_src_neo/com/maidsmart/memory/AiMemorySearch.java`:1
- `promaid_src_neo/com/maidsmart/memory/AiMemorySkipList.java`:1
