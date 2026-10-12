---
uid: fd6825c1
id: promaid.accessory.smash
parent: promaid.accessory
name: {zh: 撞击伤害与击飞, en: "Ram Damage & Knockback"}
description:
  zh: >
      滑翔中撞人：覆盖范围内的非友方单位按这一刻的速度吃伤害（速度 × 每格伤害，封顶 40）与击飞（速度 × 每格击飞，封顶
      10）。击飞方向顺着佩戴者这一刻的运动方向——平飞顺着飞出去、俯冲往下砸、爬升往上抛；水平分量走原版击退，竖直分量用 push 补。低于 0.4
      格/tick 不结算。
  en: >
      Ramming while gliding: non-friendly units in the shared footprint take
      damage scaled by the current speed (speed x per-block damage, capped at 40)
      plus knockback (speed x per-block knockback, capped at 10). The knockback
      follows the wearer's motion direction - level flight throws it forward, a
      dive slams it down, a climb tosses it up; the horizontal part is vanilla
      knockback, the vertical one added with push. Below 0.4 blocks/tick nothing
      resolves.
source:
  - {path: promaid_src_neo/com/maidsmart/accessory/DaYunSmash.java, line: 1, end_line: 155}
revision: 8fbb6334b1c5b0d47a7c9642f74052c0698d3b0f
updated_at: "2026-10-01T00:00:00Z"
fingerprint: bf19cfd158f56a88d2ea282e887fa809220b265024843d1437e561cb14173b03
state: active
tags: [accessory]
apis:
  - protocol: rpc
    path: "DaYunSmash.impact(ServerLevel, LivingEntity, Vec3, int)"
    description: {zh: 每 tick 每个佩戴者一次的撞击结算入口。, en: "Per-wearer, per-tick ram resolution entry point."}
deps:
  - {kind: call, to: promaid.accessory.breaker, label: {zh: 共用范围口径, en: Shared footprint}}
---

## 撞击伤害与击飞 · Ram Damage & Knockback

滑翔中撞人：覆盖范围内的非友方单位按这一刻的速度吃伤害（速度 × 每格伤害，封顶 40）与击飞（速度 × 每格击飞，封顶 10）。击飞方向顺着佩戴者这一刻的运动方向——平飞顺着飞出去、俯冲往下砸、爬升往上抛；水平分量走原版击退，竖直分量用 push 补。低于 0.4 格/tick 不结算。

**代码证据**

- `promaid_src_neo/com/maidsmart/accessory/DaYunSmash.java`:1
