---
uid: 707c75cc
id: promaid.system.schedule.net
parent: promaid.system.schedule
name: {zh: 排班网络包, en: Schedule Networking}
description:
  zh: >
      三条包文件：登记、计划数据、女仆操作。v1.2.4 从原网络类拆出，编解码逐字未动，三个文件共享同一段 21 行 import
      头——典型的复制粘贴拆分。
  en: >
      Three packet files: registration, plan data and maid operations. Split out
      of the original networking class in v1.2.4 with encode/decode copied
      verbatim — all three share the same 21-line import header, a textbook
      copy-paste split.
source:
  - {path: promaid_src_neo/com/maidsmart/schedule/SchedulePacketsPlan.java, line: 1, end_line: 640}
  - {path: promaid_src_neo/com/maidsmart/schedule/SchedulePacketsMaid.java, line: 1, end_line: 616}
  - {path: promaid_src_neo/com/maidsmart/schedule/ScheduleNetworking.java, line: 1, end_line: 274}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 026657fd04ca0e89505ea12a697e41749996de627d1ebaedc4e2ebf407742fda
state: active
tags: [system, net]
---

## 排班网络包 · Schedule Networking

三条包文件：登记、计划数据、女仆操作。v1.2.4 从原网络类拆出，编解码逐字未动，三个文件共享同一段 21 行 import 头——典型的复制粘贴拆分。

**代码证据**

- `promaid_src_neo/com/maidsmart/schedule/SchedulePacketsPlan.java`:1
- `promaid_src_neo/com/maidsmart/schedule/SchedulePacketsMaid.java`:1
- `promaid_src_neo/com/maidsmart/schedule/ScheduleNetworking.java`:1
