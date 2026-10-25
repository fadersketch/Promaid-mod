---
uid: a95b6376
id: promaid.combat.ride
parent: promaid.combat
name: {zh: 骑乘指挥棒, en: Ride Baton}
description:
  zh: >
      把「已上鞍的坐骑」（原版生物 / 卓越前线载具 /
      冰火传说飞龙）与女仆绑定，让她骑着它跟主人走、可攻击可飞行。顺序固定：先右击坐骑、再右击女仆；左键换座。战斗中地面寻路失败时退回 MoveControl
      直推，避免目标在围栏/窄缝里时坐骑原地不动；平时跟随不启用。
  en: >
      Binds an already-saddled mount (vanilla mob / Superb Warfare vehicle / Ice
      and Fire dragon) to a maid so she rides it following the owner, and can
      fight and fly. Order is fixed: right-click the mount first, then the maid;
      left-click swaps seats. In combat, when ground pathfinding fails, it falls
      back to a direct MoveControl push so the mount does not stand still with the
      target behind a fence or in a narrow gap; normal following keeps the old
      behaviour.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/RideBindManager.java, line: 1, end_line: 2270}
  - {path: promaid_src_neo/com/maidsmart/combat/MaidRideKit.java, line: 1, end_line: 803}
  - {path: promaid_src_neo/com/maidsmart/combat/RideBatonItem.java, line: 1, end_line: 36}
revision: f33c45a1ff17ca592b7a3d4ee76d313eb49dac00
updated_at: "2026-10-01T00:00:00Z"
fingerprint: 401f8a2164997269eb8e7548316cf0215ee855d3727224de0d7e646a9d014fd9
state: active
tags: [combat, item, mount]
apis:
  - protocol: rpc
    path: RideBindManager.bind(...)
    description: {zh: 绑定语义：一车一仆；甲的车不受乙影响。, en: "Bind semantics: one mount one maid; maid A's mount is unaffected by B."}
  - protocol: rpc
    path: RideBindManager.handleSwapSeatRequest(...)
    description: {zh: 左键换座的服务端落点。, en: Server side of the left-click seat swap.}
deps:
  - {kind: call, to: promaid.combat.mount-compat, label: {zh: 载具兼容, en: Mount compat}}
---

## 骑乘指挥棒 · Ride Baton

把「已上鞍的坐骑」（原版生物 / 卓越前线载具 / 冰火传说飞龙）与女仆绑定，让她骑着它跟主人走、可攻击可飞行。顺序固定：先右击坐骑、再右击女仆；左键换座。战斗中地面寻路失败时退回 MoveControl 直推，避免目标在围栏/窄缝里时坐骑原地不动；平时跟随不启用。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/RideBindManager.java`:1
- `promaid_src_neo/com/maidsmart/combat/MaidRideKit.java`:1
- `promaid_src_neo/com/maidsmart/combat/RideBatonItem.java`:1
