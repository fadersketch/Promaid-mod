---
uid: 4ae598c7
id: promaid.work.ai-tools
parent: promaid.work
name: {zh: 建造 AI 工具, en: AI Build Tools}
description:
  zh: >
      给 LLM 用的三件：smart_build（LLM 生成 JSON
      蓝图，含白名单与上限，整建或部分）、smart_build_list（目录查询）、smart_design（子代理建筑师，存蓝图后走常规建造流）。
  en: >
      Three tools for the LLM: smart_build (LLM-generated JSON blueprint with
      whitelist and limits, full or partial), smart_build_list (catalog query) and
      smart_design (sub-agent architect, saved blueprint, then the normal build
      flow).
source:
  - {path: promaid_src_neo/com/maidsmart/build/SmartBuildTool.java, line: 1, end_line: 407}
  - {path: promaid_src_neo/com/maidsmart/build/SmartDesignTool.java, line: 1, end_line: 333}
  - {path: promaid_src_neo/com/maidsmart/build/SmartBuildListTool.java, line: 1, end_line: 78}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 59c1b015cbcda73af07e765e87f1022d92d785f2cba7a71f70d9ef7d00ca7374
state: active
tags: [build, ai]
deps:
  - {kind: call, to: promaid.work.blueprint-lib, label: {zh: 蓝图库, en: Blueprint lib}}
---

## 建造 AI 工具 · AI Build Tools

给 LLM 用的三件：smart_build（LLM 生成 JSON 蓝图，含白名单与上限，整建或部分）、smart_build_list（目录查询）、smart_design（子代理建筑师，存蓝图后走常规建造流）。

**代码证据**

- `promaid_src_neo/com/maidsmart/build/SmartBuildTool.java`:1
- `promaid_src_neo/com/maidsmart/build/SmartDesignTool.java`:1
- `promaid_src_neo/com/maidsmart/build/SmartBuildListTool.java`:1
