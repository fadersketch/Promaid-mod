---
uid: 091f0d3e
id: promaid.system.visibility
parent: promaid.system
name: {zh: 可见性自愈, en: Visibility Self-Heal}
description:
  zh: >
      每 tick 检查服务端活着的女仆是否真的被追踪与可见，出现客户端幽灵状态就重发实体生成包（根因是区块卸载时 ChunkMap 移除实体）。
  en: >
      Per-tick check that a live server-side maid is actually tracked and visible,
      re-sending spawn packets whenever the client-side ghost state appears (root
      cause: ChunkMap removing the entity on chunk unload).
source:
  - {path: promaid_src_neo/com/maidsmart/follow/MaidVisibilityGuard.java, line: 1, end_line: 119}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: dae096c7d21896a0a095015cd8f82d02563eb46966dc7c30e68af6848fb73c2c
state: active
tags: [system]
---

## 可见性自愈 · Visibility Self-Heal

每 tick 检查服务端活着的女仆是否真的被追踪与可见，出现客户端幽灵状态就重发实体生成包（根因是区块卸载时 ChunkMap 移除实体）。

**代码证据**

- `promaid_src_neo/com/maidsmart/follow/MaidVisibilityGuard.java`:1
