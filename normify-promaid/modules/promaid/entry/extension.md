---
uid: a8e5d811
id: promaid.entry.extension
parent: promaid.entry
name: {zh: 功能注册枢纽, en: Feature Registration Hub}
description:
  zh: >
      实现 TLM 的 @LittleMaidExtension：注册 AI 工具、任务、TaskData、AI 上下文、core/rest
      大脑行为，并驱动所有服务端每 tick 模块。是全模组唯一的装配中枢，也是最大的耦合点。
  en: >
      Implements TLM's @LittleMaidExtension: registers AI tools, tasks, TaskData,
      AI contexts and core/rest brain behaviors, and drives every server-tick
      module. The single assembly hub of the whole mod, and its largest coupling
      point.
source:
  - {path: promaid_src_neo/com/maidsmart/ProMaidExtension.java, line: 593, end_line: 779}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 328415dd1df24e2cfa1edd46113be2eb2979c2862aecf35a79ad5b6772f20c10
state: active
tags: [entry, hub]
apis:
  - protocol: rpc
    path: addMaidTask(TaskManager)
    description: {zh: 向 TLM 登记本模组的全部任务。, en: Registers all of this mod's tasks with TLM.}
  - protocol: rpc
    path: addExtraMaidBrain()
    description: {zh: 登记 core/rest 大脑行为与优先级。, en: Registers core/rest brain behaviors and priorities.}
  - protocol: rpc
    path: registerAITool(...)
    description: {zh: 登记供 LLM 调用的工具。, en: Registers LLM-callable tools.}
  - protocol: event
    path: onServerTick(...)
    description: {zh: 驱动全部服务端每-tick 模块。, en: Drives every server tick module.}
---

## 功能注册枢纽 · Feature Registration Hub

实现 TLM 的 @LittleMaidExtension：注册 AI 工具、任务、TaskData、AI 上下文、core/rest 大脑行为，并驱动所有服务端每 tick 模块。是全模组唯一的装配中枢，也是最大的耦合点。

**代码证据**

- `promaid_src_neo/com/maidsmart/ProMaidExtension.java`:593
