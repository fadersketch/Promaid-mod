---
uid: a9446028
id: promaid.work.blueprint-ui
parent: promaid.work
name: {zh: 蓝图手册界面, en: Blueprint Book UI}
description:
  zh: >
      客户端 Screen（无 Menu 容器），6 个视图：主页/建造目录/材料明细/女仆管理/女仆详情/区域女仆；暂停恢复与工头控制，每 2
      秒轮询进度。2068 行单类。
  en: >
      A client Screen with no Menu container and 6 views: home, build catalog,
      material detail, maid management, maid detail and region maids; pause/resume
      and foreman control, polling progress every 2 seconds. 2068 lines in a
      single class.
source:
  - {path: promaid_src_neo/com/maidsmart/build/BlueprintBookScreen.java, line: 1, end_line: 2139}
  - {path: promaid_src_neo/com/maidsmart/build/BlueprintBookItem.java, line: 1, end_line: 93}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 78e22ab5a63b30aefea7c48cd2412c0fb23175954c5ac7b296b16b1494e6f7e5
state: active
tags: [build, ui, client]
deps:
  - {kind: call, to: promaid.guide, label: {zh: 手册, en: Guide}}
  - {kind: call, to: promaid.core.config-panel, label: {zh: 配置跳转, en: Config jump}}
---

## 蓝图手册界面 · Blueprint Book UI

客户端 Screen（无 Menu 容器），6 个视图：主页/建造目录/材料明细/女仆管理/女仆详情/区域女仆；暂停恢复与工头控制，每 2 秒轮询进度。2068 行单类。

**代码证据**

- `promaid_src_neo/com/maidsmart/build/BlueprintBookScreen.java`:1
- `promaid_src_neo/com/maidsmart/build/BlueprintBookItem.java`:1
