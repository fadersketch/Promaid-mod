---
uid: 6485bf0b
id: promaid.work.blueprint-lib.io
parent: promaid.work.blueprint-lib
name: {zh: 蓝图文件 IO, en: Blueprint File I/O}
description:
  zh: >
      扫描 config/maid_smart/blueprints 与 schematics 外部目录，7 种扩展名，zip
      导入，内置蓝图解包与预热。内置程序化生成器自 v1.5.387 起已不再被目录枚举，只可硬编码 id 触达（休眠代码）。
  en: >
      Scans the external config/maid_smart/blueprints and schematics directories,
      7 extensions, zip import and bundled-blueprint unpacking with warmup. The
      procedural built-in generator has not been enumerated by the catalog since
      v1.5.387 and is reachable only by hardcoded id (dormant code).
source:
  - {path: promaid_src_neo/com/maidsmart/build/BlueprintFileIo.java, line: 1, end_line: 725}
  - {path: promaid_src_neo/com/maidsmart/build/BlueprintCatalog.java, line: 1, end_line: 427}
  - {path: promaid_src_neo/com/maidsmart/build/BuiltinHouses.java, line: 1, end_line: 1385}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: ad093c0b1ffbcbd6a50fce4293a5191b1a0d17b30541b9208b314abab88ee8ad
state: active
tags: [build, io]
---

## 蓝图文件 IO · Blueprint File I/O

扫描 config/maid_smart/blueprints 与 schematics 外部目录，7 种扩展名，zip 导入，内置蓝图解包与预热。内置程序化生成器自 v1.5.387 起已不再被目录枚举，只可硬编码 id 触达（休眠代码）。

**代码证据**

- `promaid_src_neo/com/maidsmart/build/BlueprintFileIo.java`:1
- `promaid_src_neo/com/maidsmart/build/BlueprintCatalog.java`:1
- `promaid_src_neo/com/maidsmart/build/BuiltinHouses.java`:1
