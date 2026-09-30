---
uid: 5737fa87
id: promaid.work.build-plan
parent: promaid.work
name: {zh: 建造计划数据层, en: Build Plan Data Model}
description:
  zh: >
      计划格式是字符串表：首元素记录原点与蓝图名/id，其余记录相对坐标与方块。多区域 planId 到 PlanState，含原点/维度/朝向
      quarter/光标/延迟重试表/已放置集；按维度持久化到 BuildArchive。BuildPlan 暴露约 40 个 public
      static，是典型上帝对象。
  en: >
      The plan format is a string list: the first element records the origin plus
      blueprint name/id, the rest record relative coordinates and blocks.
      Multi-region planId to PlanState holds origin, dimension, rotation quarters,
      cursor, deferred-retry map and placed sets; persisted per dimension via
      BuildArchive. BuildPlan exposes about 40 public statics — a textbook god
      object.
source:
  - {path: promaid_src_neo/com/maidsmart/build/BuildPlan.java, line: 1, end_line: 1045}
  - {path: promaid_src_neo/com/maidsmart/build/BuildArchive.java, line: 1, end_line: 176}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: d28afba3bece0d7936d47f126efa2753a8eb9785795eccf8ce78a3e40e38fa3d
state: active
tags: [build]
---

## 建造计划数据层 · Build Plan Data Model

计划格式是字符串表：首元素记录原点与蓝图名/id，其余记录相对坐标与方块。多区域 planId 到 PlanState，含原点/维度/朝向 quarter/光标/延迟重试表/已放置集；按维度持久化到 BuildArchive。BuildPlan 暴露约 40 个 public static，是典型上帝对象。

**代码证据**

- `promaid_src_neo/com/maidsmart/build/BuildPlan.java`:1
- `promaid_src_neo/com/maidsmart/build/BuildArchive.java`:1
