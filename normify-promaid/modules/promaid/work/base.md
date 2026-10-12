---
uid: fed9c5ea
id: promaid.work.base
parent: promaid.work
name: {zh: 采集行为共享基类, en: Gathering Behavior Base}
description:
  zh: >
      挖矿与伐木两个 AI 行为逐字相同的部分上提而成的抽象基类：废石白名单、开路可破白名单、per-女仆
      状态表与纯工具函数。上提后两个子类各留自己的差异（矿表 vs 原木表、连锁规则），不再各抄一份。
  en: >
      The abstract base that mining and woodcutting now both extend, holding what
      the two AI behaviors had verbatim in common: the junk-stone whitelist, the
      open-breakable whitelist, the per-maid state map and the pure helpers. After
      the hoist each subclass keeps only its own differences (ore table vs log
      table, chain rules) instead of carrying a second copy.
source:
  - {path: promaid_src_neo/com/maidsmart/task/MaidWorkBehavior.java, line: 1, end_line: 685}
revision: 8fbb6334b1c5b0d47a7c9642f74052c0698d3b0f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: a9d3b7eda1f1842c3eecd2f49f97f4bb5892c455b7373c7e60cdd481ecf17a41
state: active
tags: [work, shared]
apis:
  - protocol: rpc
    path: MaidWorkBehavior()
    description: {zh: "共享构造（emptyMap, MAX, MAX = 无限时长）。", en: "Shared constructor (emptyMap, MAX, MAX = unlimited duration)."}
  - protocol: dataflow
    path: JUNK_STONES / OPEN_BREAKABLE
    description: {zh: 废石白名单与开路可破白名单。, en: Junk-stone and open-breakable whitelists.}
---

## 采集行为共享基类 · Gathering Behavior Base

挖矿与伐木两个 AI 行为逐字相同的部分上提而成的抽象基类：废石白名单、开路可破白名单、per-女仆 状态表与纯工具函数。上提后两个子类各留自己的差异（矿表 vs 原木表、连锁规则），不再各抄一份。

**代码证据**

- `promaid_src_neo/com/maidsmart/task/MaidWorkBehavior.java`:1
