---
uid: 2d1823ba
id: promaid.mixin.network-chunk
parent: promaid.mixin
name: {zh: 网络与区块追踪注入, en: "Network & Chunk-Tracking Patches"}
description:
  zh: >
      远程开界面绕过 4 格距离、强制实体同步（治「客户端没有她」）、远程界面同步泵。靠改写 vanilla ChunkMap 的
      pairing/seenBy 实现，脆弱且深度反射。
  en: >
      Lets the GUI open remotely past the 4-block limit, forces entity resync
      (fixes 'she is not on my client') and pumps remote GUI sync — by rewriting
      vanilla ChunkMap pairing/seenBy. Fragile and deeply reflective.
source:
  - {path: promaid_src_neo/com/maidsmart/mixin/ChunkMapTrackRemoteMixin.java, line: 1, end_line: 168}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidContainerRemoteOpenMixin.java, line: 1, end_line: 84}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidGuiPacketRemoteMixin.java, line: 1, end_line: 45}
  - {path: promaid_src_neo/com/maidsmart/mixin/ChunkMapRemotePumpMixin.java, line: 1, end_line: 70}
  - {path: promaid_src_neo/com/maidsmart/mixin/EquipmentPacketCopyMixin.java, line: 1, end_line: 47}
revision: f33c45a1ff17ca592b7a3d4ee76d313eb49dac00
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 070bce9b6348bce05c5a41757accc04968a091e90e6b56442dcd5691a23355df
state: active
tags: [mixin, net]
deps:
  - {kind: call, to: promaid.system.remote-gui, label: {zh: 远程界面, en: Remote GUI}}
  - {kind: call, to: promaid.system.visibility, label: {zh: 可见性自愈, en: Visibility self-heal}}
---

## 网络与区块追踪注入 · Network & Chunk-Tracking Patches

远程开界面绕过 4 格距离、强制实体同步（治「客户端没有她」）、远程界面同步泵。靠改写 vanilla ChunkMap 的 pairing/seenBy 实现，脆弱且深度反射。

**代码证据**

- `promaid_src_neo/com/maidsmart/mixin/ChunkMapTrackRemoteMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidContainerRemoteOpenMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidGuiPacketRemoteMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/ChunkMapRemotePumpMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/EquipmentPacketCopyMixin.java`:1
