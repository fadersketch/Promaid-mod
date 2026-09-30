---
uid: 0357923f
id: promaid.work.blueprint-formats
parent: promaid.work
name: {zh: 蓝图格式解析, en: Blueprint Format Parsing}
description:
  zh: >
      litematic：遍历 Regions，解包位压缩 BlockStates（bits 取调色板大小的对数，最少
      2），合并分区调色板，TileEntities 转方块实体 SNBT。schem（Sponge）与 schematic（MCEdit
      varint）同口径归一化。
  en: >
      litematic: walks Regions, unpacks bit-packed BlockStates (bits = log2
      palette, min 2), merges per-region palettes and maps TileEntities to
      block-entity SNBT. schem (Sponge) and schematic (MCEdit varint) normalise to
      the same shape.
source:
  - {path: promaid_src_neo/com/maidsmart/build/BlueprintStructureCodec.java, line: 1, end_line: 300}
  - {path: promaid_src_neo/com/maidsmart/build/BlueprintWorldExtract.java, line: 1, end_line: 397}
revision: 43cc86d020b9ba3a495013478435099a3c06df4f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 918d2e3c907d2da8ad0f0c6820a9a0c0374995742ff873bb842dbab06e3f7694
state: active
tags: [build]
---

## 蓝图格式解析 · Blueprint Format Parsing

litematic：遍历 Regions，解包位压缩 BlockStates（bits 取调色板大小的对数，最少 2），合并分区调色板，TileEntities 转方块实体 SNBT。schem（Sponge）与 schematic（MCEdit varint）同口径归一化。

**代码证据**

- `promaid_src_neo/com/maidsmart/build/BlueprintStructureCodec.java`:1
- `promaid_src_neo/com/maidsmart/build/BlueprintWorldExtract.java`:1
