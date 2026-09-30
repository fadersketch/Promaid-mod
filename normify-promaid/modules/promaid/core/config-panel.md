---
uid: beda6346
id: promaid.core.config-panel
parent: promaid.core
name: {zh: 配置面板 GUI, en: Config Panel GUI}
description:
  zh: >
      不是控件对象树，而是手写的立即模式界面：约 12 个嵌套类型（含 7 个分组枚举与 35 个 Section）、约 180 个方法、59
      处控件注册、32 个输入框。render 单方法 543 行，烹饪网格点击 468 行；5 组几乎一样的列表加条目对。全模组最大文件。
  en: >
      Not a widget object graph but a hand-rolled immediate-mode screen: about 12
      nested types (7 group enums, 35 sections), ~180 methods, 59 widget
      registrations and 32 text inputs. A single 543-line render and a 468-line
      cooking-grid click handler; five near-identical list-plus-entry pairs. The
      mod's largest file.
source:
  - {path: promaid_src_neo/com/maidsmart/config/PromaidConfigScreen.java, line: 1, end_line: 6424}
revision: 491ba927ffa91889b679f800817dd48763aabd62
updated_at: "2026-10-01T00:00:00Z"
fingerprint: f158bfda38550176563f7f96a5fcb560b92d26534861a1d229118b65809ec3ff
state: active
tags: [core, ui, client]
apis:
  - protocol: rpc
    path: PromaidConfigScreen.openAt(...)
    description: {zh: 从手册章节跳转到指定配置行。, en: Jumps from a guide chapter to a config row.}
---

## 配置面板 GUI · Config Panel GUI

不是控件对象树，而是手写的立即模式界面：约 12 个嵌套类型（含 7 个分组枚举与 35 个 Section）、约 180 个方法、59 处控件注册、32 个输入框。render 单方法 543 行，烹饪网格点击 468 行；5 组几乎一样的列表加条目对。全模组最大文件。

**代码证据**

- `promaid_src_neo/com/maidsmart/config/PromaidConfigScreen.java`:1
