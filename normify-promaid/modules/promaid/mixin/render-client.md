---
uid: e864f351
id: promaid.mixin.render-client
parent: promaid.mixin
name: {zh: 客户端渲染注入, en: "Client Render & Gecko Patches"}
description:
  zh: >
      鞘翅层、旋转/俯冲倾角、幽灵渲染、扫帚/拴绳虚影、Gecko 动画去重、模型包排序、金色描边（绑定期与准星指向）、自由飞行动画注册、情绪姿势覆盖。
  en: >
      Elytra layer, spin/dive tilt, ghost rendering, broom/tether ghosting, Gecko
      animation dedup, model-pack sorting, gold outline (while bound and while
      aimed at), free-flight animation registration and emotion pose override.
source:
  - {path: promaid_src_neo/com/maidsmart/mixin/EmotionPoseMixin.java, line: 1, end_line: 282}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidModelPackSortMixin.java, line: 1, end_line: 91}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidGhostTetherMixin.java, line: 1, end_line: 60}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidAimGlowMixin.java, line: 1, end_line: 33}
  - {path: promaid_src_neo/com/maidsmart/client/MaidAimGlowClient.java, line: 1, end_line: 93}
revision: f33c45a1ff17ca592b7a3d4ee76d313eb49dac00
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 242a57b0e5325ac3d8232bcef4d23ff462720052a237966a4032d0b2bdbbf810
state: active
tags: [mixin, client]
deps:
  - {kind: call, to: promaid.social.emotion, label: {zh: 情绪姿势, en: Emotion pose}}
  - {kind: call, to: promaid.combat.tether, label: {zh: 拴绳虚影, en: Tether ghost}}
---

## 客户端渲染注入 · Client Render & Gecko Patches

鞘翅层、旋转/俯冲倾角、幽灵渲染、扫帚/拴绳虚影、Gecko 动画去重、模型包排序、金色描边（绑定期与准星指向）、自由飞行动画注册、情绪姿势覆盖。

**代码证据**

- `promaid_src_neo/com/maidsmart/mixin/EmotionPoseMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidModelPackSortMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidGhostTetherMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidAimGlowMixin.java`:1
- `promaid_src_neo/com/maidsmart/client/MaidAimGlowClient.java`:1
