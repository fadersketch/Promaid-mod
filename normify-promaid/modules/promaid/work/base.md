---
uid: fed9c5ea
id: promaid.work.base
parent: promaid.work
name: {zh: 采集行为共享基类, en: Gathering Behavior Base}
description:
  zh: >
      挖矿与伐木两个 AI 行为逐字相同的部分上提而成的抽象基类：废石白名单、开路可破白名单、per-女仆
      状态表与纯工具函数。上提后两个子类各留自己的差异（矿表 vs 原木表、连锁规则），不再各抄一份。另含
      MaidWorkRangeExpand：干活期间把「工作范围」圈的半径读数临时顶到罩得住「她到主人 /
      到圈心」的距离，让跟随与排班/在家两条"太远就回去"的判据都不把她从活上拽走。
  en: >
      The abstract base that mining and woodcutting now both extend, holding what
      the two AI behaviors had verbatim in common: the junk-stone whitelist, the
      open-breakable whitelist, the per-maid state map and the pure helpers. After
      the hoist each subclass keeps only its own differences (ore table vs log
      table, chain rules) instead of carrying a second copy. Also holds
      MaidWorkRangeExpand, which temporarily inflates the restrict-radius readout
      while mining/woodcutting so it covers the distance to the owner and to the
      work center, keeping both the follow task and the schedule pull-back from
      dragging her off the job.
source:
  - {path: promaid_src_neo/com/maidsmart/task/MaidWorkBehavior.java, line: 1, end_line: 694}
  - {path: promaid_src_neo/com/maidsmart/task/MaidWorkRangeExpand.java, line: 1, end_line: 94}
revision: f94c12de83c73b74b57c4d736c614ed6bca9d571
updated_at: "2026-10-01T00:00:00Z"
fingerprint: f92cbdc45694e103cc72abb0b16d51908b469c3d01874f295942276b9102f454
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

挖矿与伐木两个 AI 行为逐字相同的部分上提而成的抽象基类：废石白名单、开路可破白名单、per-女仆 状态表与纯工具函数。上提后两个子类各留自己的差异（矿表 vs 原木表、连锁规则），不再各抄一份。另含 MaidWorkRangeExpand：干活期间把「工作范围」圈的半径读数临时顶到罩得住「她到主人 / 到圈心」的距离，让跟随与排班/在家两条"太远就回去"的判据都不把她从活上拽走。

**代码证据**

- `promaid_src_neo/com/maidsmart/task/MaidWorkBehavior.java`:1
- `promaid_src_neo/com/maidsmart/task/MaidWorkRangeExpand.java`:1
