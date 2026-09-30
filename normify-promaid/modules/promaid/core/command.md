---
uid: 7cc145c6
id: promaid.core.command
parent: promaid.core
name: {zh: 管理指令, en: Admin Commands}
description:
  zh: >
      命令树：批量召唤、记忆开关、投喂测试、飞行跟随验收、客户端重同步、Goety 飞行。五个类各自独立注册同一个字面量，没有中央命令注册表。
  en: >
      The command tree: batch summon, memory toggle, feeding test, flight-follow
      acceptance, client resync and Goety flight. Five classes each independently
      register the same literal — there is no central command registry.
source:
  - {path: promaid_src_neo/com/maidsmart/command/MaidArmyCommand.java, line: 1, end_line: 663}
  - {path: promaid_src_neo/com/maidsmart/command/MaidResyncCommand.java, line: 1, end_line: 616}
  - {path: promaid_src_neo/com/maidsmart/command/MaidGoetyFlyCommand.java, line: 1, end_line: 232}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 70e778433134adaef8bf63cb046a7b2a1d9ead37c1bc62a0ef9c0f27853dbebb
state: active
tags: [core, command]
---

## 管理指令 · Admin Commands

命令树：批量召唤、记忆开关、投喂测试、飞行跟随验收、客户端重同步、Goety 飞行。五个类各自独立注册同一个字面量，没有中央命令注册表。

**代码证据**

- `promaid_src_neo/com/maidsmart/command/MaidArmyCommand.java`:1
- `promaid_src_neo/com/maidsmart/command/MaidResyncCommand.java`:1
- `promaid_src_neo/com/maidsmart/command/MaidGoetyFlyCommand.java`:1
