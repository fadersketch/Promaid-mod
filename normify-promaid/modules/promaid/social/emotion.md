---
uid: 92a08149
id: promaid.social.emotion
parent: promaid.social
name: {zh: 亲昵互动与姿势, en: "Affection Interactions & Poses"}
description:
  zh: >
      按键 G 摸头 / H 抱抱，给好感加成、心形粒子与姿势动画；服务端校验视线/距离/主人，冷却 8 秒与 30 秒。姿势靠 mixin 覆盖 TLM
      最终骨骼层。
  en: >
      Keys G (headpat) and H (hug) grant favour, heart particles and a pose
      animation; the server checks line of sight, distance and ownership, with 8s
      and 30s cooldowns. Poses are applied by overriding TLM's final bone layer
      via mixin.
source:
  - {path: promaid_src_neo/com/maidsmart/emotion/EmotionNetworking.java, line: 1, end_line: 231}
  - {path: promaid_src_neo/com/maidsmart/emotion/EmotionPoseState.java, line: 1, end_line: 53}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 9565fd16bcaa51506dccbcf952216ec46b70adf1764ab8eef982cd0e5c1843cf
state: active
tags: [social, client]
---

## 亲昵互动与姿势 · Affection Interactions & Poses

按键 G 摸头 / H 抱抱，给好感加成、心形粒子与姿势动画；服务端校验视线/距离/主人，冷却 8 秒与 30 秒。姿势靠 mixin 覆盖 TLM 最终骨骼层。

**代码证据**

- `promaid_src_neo/com/maidsmart/emotion/EmotionNetworking.java`:1
- `promaid_src_neo/com/maidsmart/emotion/EmotionPoseState.java`:1
