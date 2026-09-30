---
uid: 8bb5a030
id: promaid.system.protect
parent: promaid.system
name: {zh: 保护与险境, en: "Protection & Hazard"}
description:
  zh: >
      主人死亡瞬间无条件立即传送所有女仆到其重生点（跨维度、强载未加载区块）；危险方块寻路避让；站在岩浆或火上的女仆每 0.5 秒巡检挪到安全格并灭火。
  en: >
      The instant the owner dies, teleport every maid to their respawn point
      unconditionally (across dimensions, force-loading chunks); pathfinding
      avoidance for dangerous blocks; and a 0.5s sweep that moves maids standing
      in lava or fire to a safe cell and extinguishes them.
source:
  - {path: promaid_src_neo/com/maidsmart/protect/MasterDeathTeleportHandler.java, line: 1, end_line: 619}
  - {path: promaid_src_neo/com/maidsmart/protect/DangerEscapeHandler.java, line: 1, end_line: 195}
  - {path: promaid_src_neo/com/maidsmart/protect/MaidDangerMalusHandler.java, line: 1, end_line: 146}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 50ccd932365c497515a62c770716ccfc8a31028ad0cc276d7d283cfcb59e40a2
state: active
tags: [system, safety]
apis:
  - protocol: event
    path: onLivingDeath(...)
    description: {zh: 主人死亡 → 全员立即传送。, en: "Owner death -> teleport everyone immediately."}
deps:
  - {kind: call, to: promaid.system.follow, label: {zh: 强载区块, en: Force-load}}
---

## 保护与险境 · Protection & Hazard

主人死亡瞬间无条件立即传送所有女仆到其重生点（跨维度、强载未加载区块）；危险方块寻路避让；站在岩浆或火上的女仆每 0.5 秒巡检挪到安全格并灭火。

**代码证据**

- `promaid_src_neo/com/maidsmart/protect/MasterDeathTeleportHandler.java`:1
- `promaid_src_neo/com/maidsmart/protect/DangerEscapeHandler.java`:1
- `promaid_src_neo/com/maidsmart/protect/MaidDangerMalusHandler.java`:1
