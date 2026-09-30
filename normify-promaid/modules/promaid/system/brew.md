---
uid: b60d6008
id: promaid.system.brew
parent: promaid.system
name: {zh: 酿造配置与配方, en: "Brewing Config & Recipes"}
description:
  zh: >
      女仆药剂手册物品右键女仆打开配置界面；两种模式——批量（有什么酿什么）与定向（按目标药水配方链精确下料，缺料等待）。配方解析靠反射读原版酿造表。
  en: >
      The brew manual item opens a config GUI on right-click: batch mode (brew
      whatever materials allow) and directed mode (follow the exact recipe chain
      for a target potion, waiting on missing ingredients). Recipe resolution
      reads vanilla brewing mixes by reflection.
source:
  - {path: promaid_src_neo/com/maidsmart/brew/BrewRecipeResolver.java, line: 1, end_line: 291}
  - {path: promaid_src_neo/com/maidsmart/brew/BrewManualScreen.java, line: 1, end_line: 607}
  - {path: promaid_src_neo/com/maidsmart/brew/BrewConfig.java, line: 1, end_line: 97}
revision: 05103ae2da3af21d7a2d344d33296407d6f26ab2
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 242f3685315d96345a95430d0a54ec90c9b8386a51da39b801b77336a45667ec
state: active
tags: [system, ui]
apis:
  - protocol: rpc
    path: BrewRecipeResolver.resolve(...)
    description: {zh: 按目标药水回溯配方链。, en: Walks the recipe chain back from a target potion.}
---

## 酿造配置与配方 · Brewing Config & Recipes

女仆药剂手册物品右键女仆打开配置界面；两种模式——批量（有什么酿什么）与定向（按目标药水配方链精确下料，缺料等待）。配方解析靠反射读原版酿造表。

**代码证据**

- `promaid_src_neo/com/maidsmart/brew/BrewRecipeResolver.java`:1
- `promaid_src_neo/com/maidsmart/brew/BrewManualScreen.java`:1
- `promaid_src_neo/com/maidsmart/brew/BrewConfig.java`:1
