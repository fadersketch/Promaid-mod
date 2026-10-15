---
uid: 31895144
id: promaid.accessory.item
parent: promaid.accessory
name: {zh: 饰品本体与音效, en: "Bauble Item & Sounds"}
description:
  zh: >
      卡车头造型的「大运」物品本体与它的音效注册。物品只提供五行可翻译说明（合成配方文案也在其中），自身不写
      NBT、不记冷却——「谁戴着」完全由饰品栏回答，反复取放、换人佩戴都不留残留。音效侧注册两首飞行 BGM 的可变衰减范围声音事件。
  en: >
      The truck-head Dayun item and its sound registration. The item only carries
      five translatable tooltip lines (the recipe text among them) and keeps no
      NBT or cooldown of its own - who wears it is answered entirely by the bauble
      slot. The sound side registers the two flight-BGM sound events (variable
      range, paired with a 200-block attenuation distance).
source:
  - {path: promaid_src_neo/com/maidsmart/accessory/DaYunItem.java, line: 1, end_line: 38}
  - {path: promaid_src_neo/com/maidsmart/accessory/DaYunSounds.java, line: 1, end_line: 52}
revision: f33c45a1ff17ca592b7a3d4ee76d313eb49dac00
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 60bc6bebd754496955e677141b909f5e2bdc4288422b274eecfd85547980470e
state: active
tags: [accessory]
apis:
  - protocol: rpc
    path: DaYunItem.appendHoverText(...)
    description: {zh: 五行可翻译说明（配方文案也在其中）。, en: Five translatable tooltip lines (recipe text included).}
  - protocol: event
    path: DaYunSounds.register(IEventBus)
    description: {zh: 把两首 BGM 的声音事件注册到 MOD 总线。, en: Registers both BGM sound events on the mod bus.}
  - protocol: rpc
    path: DaYunSounds.forTrack(int)
    description: {zh: 曲目序号 → 声音事件。, en: "Track index -> sound event."}
---

## 饰品本体与音效 · Bauble Item & Sounds

卡车头造型的「大运」物品本体与它的音效注册。物品只提供五行可翻译说明（合成配方文案也在其中），自身不写 NBT、不记冷却——「谁戴着」完全由饰品栏回答，反复取放、换人佩戴都不留残留。音效侧注册两首飞行 BGM 的可变衰减范围声音事件。

**代码证据**

- `promaid_src_neo/com/maidsmart/accessory/DaYunItem.java`:1
- `promaid_src_neo/com/maidsmart/accessory/DaYunSounds.java`:1
