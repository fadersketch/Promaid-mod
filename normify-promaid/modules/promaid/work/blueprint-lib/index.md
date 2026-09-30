---
uid: 85a57dac
id: promaid.work.blueprint-lib
parent: promaid.work
name: {zh: 蓝图库门面, en: Blueprint Facade}
description:
  zh: >
      570 行门面：静态访问器加 73 个一行转发到 14 个拆分实现类（v1.2.4 拆分）。拆分只做了一半——门面里仍混着真实逻辑，且 14
      个类都带着同一段用不上的 import。
  en: >
      A 570-line facade: static accessors plus 73 one-line delegations to 14 split
      implementation classes (split in v1.2.4). The split is half-done — real
      logic still sits in the facade, and all 14 classes carry the same unused
      import block.
source:
  - {path: promaid_src_neo/com/maidsmart/build/BlueprintLib.java, line: 1, end_line: 570}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 2f2e71a477c3862d9d9a1864f57020bcd1826a277df280d88d0d750ce1170916
state: active
tags: [build]
---

## 蓝图库门面 · Blueprint Facade

570 行门面：静态访问器加 73 个一行转发到 14 个拆分实现类（v1.2.4 拆分）。拆分只做了一半——门面里仍混着真实逻辑，且 14 个类都带着同一段用不上的 import。

**代码证据**

- `promaid_src_neo/com/maidsmart/build/BlueprintLib.java`:1
