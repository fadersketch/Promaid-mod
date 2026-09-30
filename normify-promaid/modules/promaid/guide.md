---
uid: 0b391fb8
id: promaid.guide
parent: promaid
name: {zh: 详细介绍手册, en: In-Game Guide}
description:
  zh: >
      手册物品的「详细介绍」子界面：36 章（35
      正文加更新日志），章节目录到正文按像素换行分页阅读；每章末尾自动附可点击「配置入口」跳转到配置面板对应行。手册只讲当前行为，更新日志另存。
  en: >
      The guide item's 'in detail' sub-screen: 36 chapters (35 content plus the
      changelog), a chapter list into pixel-wrapped paged text. Each chapter ends
      with a clickable 'config entry' link jumping to the matching row of the
      config panel. The guide documents only current behavior; the changelog is
      kept separate.
source:
  - {path: promaid_src_neo/com/maidsmart/guide/GuideContent.java, line: 1, end_line: 207}
  - {path: promaid_src_neo/com/maidsmart/guide/GuideScreen.java, line: 1, end_line: 826}
  - {path: promaid_src_neo/com/maidsmart/guide/GuideChaptersFlight.java, line: 1, end_line: 1282}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 3594e84072cc777d39d922a192cfc556148d85166b68057c3690e59e3f1332ac
state: active
tags: [guide, ui]
apis:
  - protocol: rpc
    path: GuideContent.chapters()
    description: {zh: 36 章注册表。, en: The 36-chapter registry.}
  - protocol: rpc
    path: GuideScreen.buildLines(...)
    description: {zh: 按像素宽度换行分页。, en: Pixel-width wrapping and paging.}
deps:
  - {kind: call, to: promaid.core.config-panel, label: {zh: 配置跳转, en: Config jump}}
---

## 详细介绍手册 · In-Game Guide

手册物品的「详细介绍」子界面：36 章（35 正文加更新日志），章节目录到正文按像素换行分页阅读；每章末尾自动附可点击「配置入口」跳转到配置面板对应行。手册只讲当前行为，更新日志另存。

**代码证据**

- `promaid_src_neo/com/maidsmart/guide/GuideContent.java`:1
- `promaid_src_neo/com/maidsmart/guide/GuideScreen.java`:1
- `promaid_src_neo/com/maidsmart/guide/GuideChaptersFlight.java`:1
