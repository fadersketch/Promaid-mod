---
uid: e95837d5
id: promaid.work.blueprint-ghost
parent: promaid.work
name: {zh: 建造投影与区域框, en: "Ghost Projection & Region Boxes"}
description:
  zh: >
      只读渲染：每个计划一个红色固定区域框（名字加创建坐标）、计划原点的橙色幽灵方块、确认流程里跟随玩家的金色预览与青色幽灵。点云由服务端采样、壳过滤、缓存。
  en: >
      Client-only rendering: a red fixed region box per plan (name plus creation
      coords), orange ghost blocks at the plan origin, and a gold player-following
      preview with cyan ghosts during the confirmation flow. The point cloud is
      sampled, shell-filtered and cached server-side.
source:
  - {path: promaid_src_neo/com/maidsmart/build/BlueprintAreaPreview.java, line: 1, end_line: 596}
  - {path: promaid_src_neo/com/maidsmart/build/BuildHudRenderer.java, line: 1, end_line: 228}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: e8aceea817236a1e9de823941d5355fb64b3e50ebac0dd162f1b325d96eb07d7
state: active
tags: [build, render, client]
---

## 建造投影与区域框 · Ghost Projection & Region Boxes

客户端只读渲染：每个计划一个红色固定区域框（名字加创建坐标）、计划原点的橙色幽灵方块、确认流程里跟随玩家的金色预览与青色幽灵。点云由服务端采样、壳过滤、缓存。

**代码证据**

- `promaid_src_neo/com/maidsmart/build/BlueprintAreaPreview.java`:1
- `promaid_src_neo/com/maidsmart/build/BuildHudRenderer.java`:1
