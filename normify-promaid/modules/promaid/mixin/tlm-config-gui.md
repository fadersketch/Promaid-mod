---
uid: 688845e3
id: promaid.mixin.tlm-config-gui
parent: promaid.mixin
name: {zh: TLM 配置界面扩展, en: TLM Config GUI Extensions}
description:
  zh: >
      在 TLM 右键配置界面注入「长期记忆」「自由飞行」「Goety」三个面板。
  en: >
      Injects three panels — long-term memory, free flight and Goety — into TLM's
      right-click config GUI.
source:
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidConfigMemoryMixin.java, line: 1, end_line: 323}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidConfigFreeFlightMixin.java, line: 1, end_line: 114}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidConfigGoetyMixin.java, line: 1, end_line: 106}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 0b6b55d513f72afe9a86ca06e4319f5eb5e93b61d3307286e31547101794be3f
state: active
tags: [mixin]
deps:
  - {kind: call, to: promaid.social.memory, label: {zh: 记忆面板, en: Memory panel}}
  - {kind: call, to: promaid.flight.free, label: {zh: 飞行开关, en: Flight toggle}}
---

## TLM 配置界面扩展 · TLM Config GUI Extensions

在 TLM 右键配置界面注入「长期记忆」「自由飞行」「Goety」三个面板。

**代码证据**

- `promaid_src_neo/com/maidsmart/mixin/MaidConfigMemoryMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidConfigFreeFlightMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidConfigGoetyMixin.java`:1
