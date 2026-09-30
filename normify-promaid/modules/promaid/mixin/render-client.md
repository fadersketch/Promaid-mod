---
uid: e864f351
id: promaid.mixin.render-client
parent: promaid.mixin
name: {zh: 客户端渲染注入, en: "Client Render & Gecko Patches"}
description:
  zh: >
      鞘翅层、旋转/俯冲倾角、幽灵渲染、扫帚/拴绳虚影、Gecko 动画去重、模型包排序、金色描边、自由飞行动画注册、情绪姿势覆盖。
  en: >
      Elytra layer, spin/dive tilt, ghost rendering, broom/tether ghosting, Gecko
      animation dedup, model-pack sorting, gold outline, free-flight animation
      registration and emotion pose override.
source:
  - {path: promaid_src_neo/com/maidsmart/mixin/EmotionPoseMixin.java, line: 1, end_line: 282}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidModelPackSortMixin.java, line: 1, end_line: 91}
  - {path: promaid_src_neo/com/maidsmart/mixin/MaidGhostTetherMixin.java, line: 1, end_line: 60}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: e510d7821d03488309df1ad3f90fd1b01598542db520883050078b448ee93bbf
state: active
tags: [mixin, client]
deps:
  - {kind: call, to: promaid.social.emotion, label: {zh: 情绪姿势, en: Emotion pose}}
  - {kind: call, to: promaid.combat.tether, label: {zh: 拴绳虚影, en: Tether ghost}}
---

## 客户端渲染注入 · Client Render & Gecko Patches

鞘翅层、旋转/俯冲倾角、幽灵渲染、扫帚/拴绳虚影、Gecko 动画去重、模型包排序、金色描边、自由飞行动画注册、情绪姿势覆盖。

**代码证据**

- `promaid_src_neo/com/maidsmart/mixin/EmotionPoseMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidModelPackSortMixin.java`:1
- `promaid_src_neo/com/maidsmart/mixin/MaidGhostTetherMixin.java`:1
