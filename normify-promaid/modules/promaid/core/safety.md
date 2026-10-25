---
uid: fd57d393
id: promaid.core.safety
parent: promaid.core
name: {zh: 建构安全与共享工具, en: "Build Safety & Shared Utilities"}
description:
  zh: >
      桥选材的统一安全过滤、无主降级、悬空禁搭、危险方块表、天然方块黑白名单、实体快照遍历、静态状态表护栏、日志、物品归还、额外容器、手持光源、临时探针；另有
      512 格长射线选女仆与原版/他模组消息的归属判定（只静默本模组发出的提示）。
  en: >
      The unified safety filter for every block a maid places: ownerless
      downgrade, no-building-while-airborne, dangerous-block tables, natural-block
      allow/deny lists, entity-snapshot iteration, static-state-table guards,
      logging, item give-back, extra containers and a held light source; plus the
      512-block long-ray maid pick and the origin check that silences only this
      mod's own messages.
source:
  - {path: promaid_src_neo/com/maidsmart/tool/MaidBuildBlockFilter.java, line: 1, end_line: 452}
  - {path: promaid_src_neo/com/maidsmart/tool/MaidPlaceGuard.java, line: 1, end_line: 268}
  - {path: promaid_src_neo/com/maidsmart/tool/MaidExtraContainer.java, line: 1, end_line: 352}
  - {path: promaid_src_neo/com/maidsmart/tool/MaidFarPick.java, line: 1, end_line: 55}
  - {path: promaid_src_neo/com/maidsmart/tool/PromaidHintGate.java, line: 1, end_line: 43}
revision: f33c45a1ff17ca592b7a3d4ee76d313eb49dac00
updated_at: "2026-10-01T00:00:00Z"
fingerprint: c0a8ec20968a68abee8fac5059420b0d2b1c724d4b8b18c407d644470d6f9632
state: active
tags: [core, shared]
---

## 建构安全与共享工具 · Build Safety & Shared Utilities

女仆一切垫脚与搭桥选材的统一安全过滤、无主降级、悬空禁搭、危险方块表、天然方块黑白名单、实体快照遍历、静态状态表护栏、日志、物品归还、额外容器、手持光源、临时探针；另有 512 格长射线选女仆与原版/他模组消息的归属判定（只静默本模组发出的提示）。

**代码证据**

- `promaid_src_neo/com/maidsmart/tool/MaidBuildBlockFilter.java`:1
- `promaid_src_neo/com/maidsmart/tool/MaidPlaceGuard.java`:1
- `promaid_src_neo/com/maidsmart/tool/MaidExtraContainer.java`:1
- `promaid_src_neo/com/maidsmart/tool/MaidFarPick.java`:1
- `promaid_src_neo/com/maidsmart/tool/PromaidHintGate.java`:1
