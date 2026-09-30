---
uid: ff709a12
id: promaid.system.home
parent: promaid.system
name: {zh: Home 巡逻与工作驱动, en: "Home Patrol & Work Movement"}
description:
  zh: >
      每 4 秒给非工作的 home 模式女仆一个家锚点附近的随机走动目标，让她不再呆立；另有每 5 tick 的直连导航驱动，只服务 home
      模式与宰杀任务（农耕在 v1.2.4 明确移除），完全绕过 TLM 大脑活动。
  en: >
      Every 4 seconds gives a non-working home-mode maid a random walk target near
      her home anchor so she stops standing still; a separate 5-tick
      direct-navigation driver serves only home mode and the slaughter task
      (farming was explicitly removed in v1.2.4), bypassing the TLM brain
      entirely.
source:
  - {path: promaid_src_neo/com/maidsmart/follow/HomePatrolHandler.java, line: 1, end_line: 116}
  - {path: promaid_src_neo/com/maidsmart/follow/HomeWorkMovementDriver.java, line: 1, end_line: 147}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: dd62f62644b4a821d86b8763e2bab1770f84a44bbad5c0ec5f3ae67fe47397b9
state: active
tags: [system]
---

## Home 巡逻与工作驱动 · Home Patrol & Work Movement

每 4 秒给非工作的 home 模式女仆一个家锚点附近的随机走动目标，让她不再呆立；另有每 5 tick 的直连导航驱动，只服务 home 模式与宰杀任务（农耕在 v1.2.4 明确移除），完全绕过 TLM 大脑活动。

**代码证据**

- `promaid_src_neo/com/maidsmart/follow/HomePatrolHandler.java`:1
- `promaid_src_neo/com/maidsmart/follow/HomeWorkMovementDriver.java`:1
