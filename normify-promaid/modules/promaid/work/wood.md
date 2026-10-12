---
uid: 6c83dc7e
id: promaid.work.wood
parent: promaid.work
name: {zh: 伐木, en: Woodcutting}
description:
  zh: >
      与挖矿同构，表换成原木/竹子（logs 标签自动），树叶不挡视线，连锁砍整棵树。与挖矿共用的静态表、状态表与纯工具已上提到
      MaidWorkBehavior，不再整份克隆挖矿。
  en: >
      Isomorphic to mining with the table swapped to logs/bamboo (auto logs tag);
      leaves do not block line of sight and the whole tree is felled in a chain.
      The static tables, state map and pure helpers shared with mining were
      hoisted into MaidWorkBehavior, so it is no longer a full clone of mining.
source:
  - {path: promaid_src_neo/com/maidsmart/task/MaidWoodBehavior.java, line: 1, end_line: 2300}
  - {path: promaid_src_neo/com/maidsmart/task/MaidWoodTask.java, line: 1, end_line: 66}
revision: 8fbb6334b1c5b0d47a7c9642f74052c0698d3b0f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 16b2a4f2b9d6921d1030cf7c2105f01ba88cdd39c788c9ecf21aeeed494910d3
state: active
tags: [work]
deps:
  - {kind: call, to: promaid.work.base, label: {zh: 共享基类, en: Shared base}}
  - {kind: call, to: promaid.work.mine, label: {zh: 同构来源, en: Isomorphic origin}}
  - {kind: call, to: promaid.work.placed-block, label: {zh: 方块回收, en: Block reclaim}}
---

## 伐木 · Woodcutting

与挖矿同构，表换成原木/竹子（logs 标签自动），树叶不挡视线，连锁砍整棵树。与挖矿共用的静态表、状态表与纯工具已上提到 MaidWorkBehavior，不再整份克隆挖矿。

**代码证据**

- `promaid_src_neo/com/maidsmart/task/MaidWoodBehavior.java`:1
- `promaid_src_neo/com/maidsmart/task/MaidWoodTask.java`:1
