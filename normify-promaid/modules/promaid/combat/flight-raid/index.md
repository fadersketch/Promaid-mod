---
uid: bc08a9f6
id: promaid.combat.flight-raid
parent: promaid.combat
name: {zh: 空袭（近战/远程）, en: Air Raid (Melee / Ranged)}
description:
  zh: >
      鞘翅加武器加飞行道具的模式：遇敌起跳滑翔、放烟花推进、爬升/环绕/收翅俯冲、近战一记或远程持续开火，循环。主手长矛时按附近敌人数分流：>3 只由独立的
      SpearAirRaidBehavior 接管（35° 爬升、浅角俯冲、贴地保护、接近后长矛水平点火贯穿、命中后水平保持再点火）；≤3 只（尤其是
      boss）继续走通用空袭，只在出手那一下换成 0.5 秒直线冲锋（速度随 MOVEMENT_SPEED
      起飞窗口都已结束）。长矛进场在爬升结束收掉自推鞘翅/激流的持续推力，并把水平速度按当前高度差压回下降曲线可行的区间，不用烟花起飞也能瞄到；飞行路径及周边
      3×3×3 格内的合法目标会强制补一次长矛结算，落地那一拍也会对身边 3×3×3 内的目标补一次。
  en: >
      Elytra plus weapon plus flight item: on spotting an enemy she launches,
      boosts with fireworks, climbs/orbits/folds her wings to dive, lands one
      melee hit or keeps firing at range, then loops. With a spear, routing
      depends on nearby threat count: above three, the dedicated
      SpearAirRaidBehavior takes over (35-degree climb, shallow dive, ground-skim
      guard, then a level spear ignition pass, hit-triggered horizontal hold,
      relaunch); at three or fewer (bosses especially) the generic chain stays in
      charge, with the spear strike replaced by a 0.5-second straight charge
      scaled by MOVEMENT_SPEED once she has cleared the ground and the launch
      window has ended. The spear approach drops self-propelled/riptide thrust at
      the end of the climb and trims horizontal speed to what the dive curve can
      still achieve, so takeoffs without fireworks can aim too. Valid targets
      within a 3×3×3 box around the flight path are force-hit once per pass;
      touching down forces one more 3×3×3 sweep in both the dedicated and the
      generic spear chain, so a landing beside a mob still deals damage before she
      relaunches. Firework thrust is applied directly by MaidVirtualFirework
      (vanilla v←v×0.5+look×0.85, no rocket entity; the trail is one vanilla-style
      particle per tick emitted client-side at her interpolated render position
      via FireworkTrailNetworking); self-propelled wings use the same plain-elytra
      thrust without consuming fireworks. Spear forced hits add the spearcore
      speed-based damage bonus. Group routing counts valid threats within a
      100-block radius.
source:
  - {path: promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java, line: 1, end_line: 3693}
  - {path: promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java, line: 1, end_line: 1201}
  - {path: promaid_src_neo/com/maidsmart/combat/MaidVirtualFirework.java, line: 1, end_line: 159}
  - {path: promaid_src_neo/com/maidsmart/combat/FireworkTrailNetworking.java, line: 1, end_line: 88}
  - {path: promaid_src_neo/com/maidsmart/client/FireworkTrailClient.java, line: 1, end_line: 128}
  - {path: promaid_src_neo/com/maidsmart/combat/MaidFlightKit.java, line: 1, end_line: 1748}
  - {path: promaid_src_neo/com/maidsmart/combat/MaidFlightRangedTask.java, line: 1, end_line: 707}
revision: f33c45a1ff17ca592b7a3d4ee76d313eb49dac00
updated_at: "2026-10-01T00:00:00Z"
fingerprint: f99099c3642d29a2586f1b7ef85d8570900076ce1c3ebdc51609920805830cea
state: active
tags: [combat, flight]
deps:
  - {kind: call, to: promaid.combat.targeting, label: {zh: 索敌, en: Targeting}}
  - {kind: call, to: promaid.combat.bombing, label: {zh: 轰炸, en: Bombing}}
  - {kind: call, to: promaid.combat.maneuvers, label: {zh: 战术机动, en: Maneuvers}}
---

## 空袭（近战/远程） · Air Raid (Melee / Ranged)

鞘翅加武器加飞行道具的模式：遇敌起跳滑翔、放烟花推进、爬升/环绕/收翅俯冲、近战一记或远程持续开火，循环。主手长矛时按附近敌人数分流：>3 只由独立的 SpearAirRaidBehavior 接管（35° 爬升、浅角俯冲、贴地保护、接近后长矛水平点火贯穿、命中后水平保持再点火）；≤3 只（尤其是 boss）继续走通用空袭，只在出手那一下换成 0.5 秒直线冲锋（速度随 MOVEMENT_SPEED 加成，起手要求离地与起飞窗口都已结束）。长矛进场在爬升结束收掉自推鞘翅/激流的持续推力，并把水平速度按当前高度差压回下降曲线可行的区间，不用烟花起飞也能瞄到；飞行路径及周边 3×3×3 格内的合法目标会强制补一次长矛结算，落地那一拍也会对身边 3×3×3 内的目标补一次。

**代码证据**

- `promaid_src_neo/com/maidsmart/combat/MaidFlightCombatBehavior.java`:1
- `promaid_src_neo/com/maidsmart/combat/SpearAirRaidBehavior.java`:1
- `promaid_src_neo/com/maidsmart/combat/MaidVirtualFirework.java`:1
- `promaid_src_neo/com/maidsmart/combat/FireworkTrailNetworking.java`:1
- `promaid_src_neo/com/maidsmart/client/FireworkTrailClient.java`:1
- `promaid_src_neo/com/maidsmart/combat/MaidFlightKit.java`:1
- `promaid_src_neo/com/maidsmart/combat/MaidFlightRangedTask.java`:1
