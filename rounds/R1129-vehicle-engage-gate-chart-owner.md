# R1129 载具接敌闸 / 巡逻航图署名与归属

## 排班

- **跑道**：C 功能修复 + 小功能（玩家两条反馈；版本号仍 1.3.3，同号重打）。
- **开工前读过**：`FlightTargeting`、`RideBindManager` 的飞行/地面载具分支、`MaidMountCompat.tickAttack` /
  `gunsFor` / `gunDataOf` / `ammoAvailable` / `vehicleHasInfiniteAmmo`、`AutoCombatPools.hasWeaponForTask`、
  `MaidBroomKit.isModeActive`、`MaidFlightKit.isModeActive`、`GunCompat.hasGunAndAmmo`、
  `PatrolChartItem` / `PatrolChartData` / `PatrolBook`、`PatrolNetworking` 的打点与开屏链路。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 未 IAttack 的载具不锁敌、不接敌移动 | 索敌器与 brain 每拍清空，载具只走跟随/巡逻 | ☑ |
| 2 | IAttack 但双方都打不了时也不接敌 | 她自己无武器/无弹 且 车里无可用弹 → 同上 | ☑ |
| 3 | 初始航图不带附魔光 | `isFoil` 改为按"是否打过有效标记" | ☑ |
| 4 | 用过的航图改名/描述变色、加署名 | 名字金色 + 署名；tooltip 金色署名行 | ☑ |
| 5 | 署名航图只认原作者 | 别人右键收到系统提示"无法使用" | ☑ |
| 6 | 两树编译/14 护栏/双 jar/部署/四测试/轮次文档 | 全绿 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者，见文末「验收」。

## 需求 / 来源

1. 女仆载具锁敌：未处于 `IAttack` 状态时，载具不执行锁敌链路和移动逻辑（无视周边敌人）；
   处于 `IAttack` 时，若她自己打不了且载具内无弹药，也不执行。她自己能打 **或** 载具能打，
   才执行——目的是不让没有攻击能力的女仆追着怪跑。
2. 初始巡逻航图不给附魔光；真正用它标记过有效点之后才给，并且物品名与描述变色、获得署名，
   表示这是特殊航图；别的玩家捡起右键会提示无法使用。

## 评估

- **载具锁敌**：`FlightTargeting` 是扫帚/鞘翅/飞行载具/地面载具共用的索敌器，它只看
  "坐骑类型 + 活敌人"，不看任务类型与攻击能力；`RideBindManager` 在索敌器返回 null 时还会
  回退读 brain，地面载具另有 `tryEngageRider` 主动评估。三处都会让"没有攻击能力"的女仆
  持续追怪。`IAttack` 在本模组语境下即 TLM 的 `IAttackTask`（扫帚/飞行远战任务也实现它）。
- **航图**：`PatrolChartItem` 的 `isFoil` 是常量 `true`；物品数据全在 NBT 的 `PatrolBook` 根键下，
  没有署名/归属字段，右键也没有任何使用者校验。

## 改动

### 载具接敌闸（两树）

- `MaidMountCompat.riderEngageAllowed(EntityMaid)`：只对 `Kind.VEHICLE` 生效——
  ① `maid.getTask() instanceof IAttackTask`；② `maidCanAttackHere` **或** `mountCanFight`。
  非卓越前线载具（扫帚/鞘翅/步行）恒返回 true，原有行为不变。
- `maidCanAttackHere`：扫帚走 `MaidBroomKit.isModeActive`、飞行任务走 `MaidFlightKit.isModeActive`、
  枪械任务走 `GunCompat.hasGunAndAmmo`、其余攻击任务走 `AutoCombatPools.hasWeaponForTask`。
- `mountCanFight`：无限弹盒 → true；否则任一炮位的当前模式 `ammoAvailable`，或者车容器里
  有任一 `GunCompat.isAmmo` 的备用弹。
- `FlightTargeting.resolve` 开头加闸：不放行就清锁/清扫描冷却并 `eraseBrain`（含
  `ATTACK_TARGET`、`LOOK_TARGET`），当作周围没有敌人。
- `RideBindManager`：飞行/地面两处 `liveTargetOf` 回退都要求 `riderEngageAllowed`；
  `tryEngageRider` 的主动评估同样加闸。
- `MaidMountCompat.tickAttack`：不放行时清掉她自己与炮塔的 AI 目标后直接返回，不再开火。

### 巡逻航图署名（两树）

- `PatrolChartData`：物品根标签新增 `chart_owner` / `chart_owner_name` / `chart_marked`；
  `marked`（是否真正用过）、`ownerName`、`canUse`（认 UUID）、`sign`（第一次有效标记时署名，
  已有署名不覆盖）、`refreshMarked`（旧版打过点的书补标志，不署名）。
- `PatrolNetworking`：轨道打点成功、目标点打点成功后 `sign`；`openFor` 时 `refreshMarked`。
- `PatrolChartItem`：`isFoil` / `getName` 改为按 `marked` 判定——没用过是普通物品；
  用过则永久附魔光、名字金色且为「某某的巡逻航图」，tooltip 多一行金色署名。
  右键前先过 `canUse`，非作者只收到一条系统提示，不打开界面。
- 四个语言文件新增 `signed` / `signed.desc` / `marked.desc` / `locked` 四条文案（zh/en，两树）。

## 验证

- 两树编译 `EXITCODE=0`。
- 14 项护栏 `FAIL 0 / WARN 0`（含 `check_workflow`）。
- 打包 + 部署 4 实例 `match=True`。
- 四套专服回归 PASS：`test_server 1201`、`test_server neoforge1211`、
  `test_maid_load 1201`、`test_maid_load neoforge1211`。
- Forge jar：18,205,371 字节，SHA256
  `C89396183B9A40CCC1F8D33C94C8C2E57E987DF441465815C7EA7D2F83E141CA`。
- Neo jar：18,266,547 字节，SHA256
  `5C5320BE2F0AC31854646F060E5DEE0557FFDA09D64B5AA3B64B1325BAFF26BE`。
- **反证**：
  - 闸只认 `Kind.VEHICLE`：扫帚、鞘翅、步行、冰火龙的索敌路径一个字节都不改。
  - "她自己能打"复用各模式自己的门禁，没有新写第二套武器/弹药判据；异常一律当作"打不了"。
  - `sign` 只写一次署名，转手后署名仍是原作者；`canUse` 没有署名时放行，避免老航图被锁死。
  - `marked` 先看持久标志、再看当前内容；清空标记不会让已用过的航图退回普通物品。

## 边界

- 载具"能打"按**当前弹种**与车容器里的备用弹判断；弹种都不对且容器无匹配弹 = 视为无攻击能力。
- 接敌闸不放行时载具仍可跟随/巡逻/做任务，只是不锁定、不朝敌人移动。
- 老版本打过点的航图：首次打开时补"用过"标志并恢复附魔光；没有原作者信息，署名留空（名字仍为金色）。
- 航图归属按玩家 UUID 判定；离线/换号无法通过（属于预期）。
- 版本号仍为 **1.3.3**，同号重打；发布素材等验收后再写。

## 验收

1. 让一个非攻击任务（如空闲/农田）的女仆坐上带武器的载具：她不再追附近的怪，只跟随/做自己的事。
2. 攻击任务女仆 + 空载具（无弹、她也无武器）：同样不追怪；给她一把有弹的枪或往车里装弹后恢复接敌。
3. 新做的巡逻航图拿在手里：没有附魔光，名字是普通颜色。
4. 用它标记第一个有效点后：出现附魔光，名字变金色并带「某某的巡逻航图」，tooltip 出现金色署名。
5. 把署名的航图丢给另一个玩家：对方右键只收到「这不是你的巡逻航图，无法使用」，界面不打开。
