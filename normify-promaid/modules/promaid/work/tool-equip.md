---
uid: 8e481204
id: promaid.work.tool-equip
parent: promaid.work
name: {zh: 任务工具自动装备, en: Auto Tool Equip}
description:
  zh: >
      核心行为优先级 200：按当前任务把背包里对口的工具/武器换到真正的主手；不占行为槽。同时也是副手姿势道具的还原入口。
  en: >
      Core behavior at priority 200: swaps the right tool/weapon for the current
      task from her backpack into the real main hand without occupying a behavior
      slot. Also the restore entry point for held-pose props.
source:
  - {path: promaid_src_neo/com/maidsmart/task/MaidToolAutoEquip.java, line: 1, end_line: 934}
  - {path: promaid_src_neo/com/maidsmart/task/MaidToolAutoEquipBehavior.java, line: 1, end_line: 122}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: ba2835c006af8c128d7d3af8caa4edd98afcafa256d7fa45b51accd8ab8e0f7a
state: active
tags: [work, shared]
---

## 任务工具自动装备 · Auto Tool Equip

核心行为优先级 200：按当前任务把背包里对口的工具/武器换到真正的主手；不占行为槽。同时也是副手姿势道具的还原入口。

**代码证据**

- `promaid_src_neo/com/maidsmart/task/MaidToolAutoEquip.java`:1
- `promaid_src_neo/com/maidsmart/task/MaidToolAutoEquipBehavior.java`:1
