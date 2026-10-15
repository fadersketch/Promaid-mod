# R978 · 垫块前先转身（修「搭路倒着搭、倒着走」）

## 排班

1. 复现路径：伐木/挖矿搭路（垫桥块、下行台阶）时头朝向与前进方向无关，视觉上倒着搭、倒着走。
2. 定位到根因：垫块后的「走上去」是**直接施速位移**（`BlockWalkOn` / `BridgeUpBehavior.stepOnto`），
   全程不写朝向；女仆的 yaw 保持垫块前的值。
3. 加 `BlockWalkOn.faceToward(maid, tx, tz)`；在**垫块之前**把身子转到即将垫的那一格，
   并在持续推送的每一拍保持朝向一致。
4. 编译 / 护栏 / 打包 / 部署 / 回归 / 文档台账。

## 需求 / 来源

- 「女仆在伐木的工作下，搭路的方向有时候是倒着搭的，倒着走。其实挖矿也会有这样的问题。
  因为代码上是垫一个就把女仆往那个方向拽。而不是调用女仆自己的行动。所以说女仆的头朝向
  跟搭路的方向没有必然关系。」
- 「解决这个问题倒也好办。在执行向前地垫方块之前，把女仆的身子转到那个准备垫的方向就行了。」

## 改法

### 根因

挖矿 / 伐木 / 搭路这三条链路在垫出一块桥块或台阶之后，走的都是**不经过导航的直接施速**：

- `MaidMineBehavior.bridgeToOre` / `MaidWoodBehavior.bridgeToWood` / 两个 `descendStep` 垫完块调
  `startStepOn(...)` → `BlockWalkOn.start(maid, tx+0.5, y, tz+0.5)`，随后每 tick 由 `BlockWalkOn.tick`
  直接 `setDeltaMovement` 推向目标格；
- `BridgeUpBehavior` 的空中铺桥 / 下行台阶则走本类自己的 `beginWalkOn` → `stepOnto`，同样是直接施速。

两条路都**只给速度、不写朝向**：她的 `yRot` / `bodyRot` / `headRot` 还是垫块前那一下的值，
于是「往东推、脸朝西」——看起来就是倒着搭、倒着走。导航走路（`setWalkTarget`）不会有这个问题，
因为 TLM 的走路逻辑自己会转身，这也解释了为什么只有「搭路/搭台阶」这一小段表现得明显。

### 改法

1. 新增 `BlockWalkOn.faceToward(EntityMaid maid, double tx, double tz)`：把朝向转到 `(tx, tz)`——
   `yaw = atan2(dz, dx)·180/π − 90`（与原版 yaw 约定一致，`RideBindManager` / 飞行链路同一套口径），
   然后**三个角度一起写**：`setYRot`（实体朝向）+ `setYBodyRot`（身体）+ `setYHeadRot`（头）。
   两树镜像（Neo 侧为 `setYRot` / `setYBodyRot` / `setYHeadRot`）。
2. `BlockWalkOn.start(...)` 登记时就调一次 `faceToward`；`BlockWalkOn.tick(...)` 在真正推送那一支里
   每拍再调一次——推送方向即朝向，方向中途变化也跟得上。
3. 四个垫块点（挖矿 `bridgeToOre` / `descendStep`、伐木 `bridgeToWood` / `descendStep`）
   在 `takeBuildBlock` **之前**调 `BlockWalkOn.faceToward(maid, tx + 0.5, tz + 0.5)`——
   先转身、再取料、再垫块，摆臂动画也朝着要垫的那一格。
4. `BridgeUpBehavior`：空中铺桥与下行台阶两处在 `takeBuildBlock` 之前同样先转身；
   `stepOnto` 的水平支路（`d > 1e-3`）补一次 `faceToward`。

没加配置开关：这是朝向与位移不一致的**纯表现/行为正确性修复**，没有可供玩家取舍的手感差异。

## 触及文件

- 两树：`com/maidsmart/task/BlockWalkOn.java`（新增 `faceToward` + 两个调用点）、
  `com/maidsmart/task/BridgeUpBehavior.java`（三处）、`com/maidsmart/task/MaidMineBehavior.java`（两处）、
  `com/maidsmart/task/MaidWoodBehavior.java`（两处）。
- 文档：`rounds/R978-face-before-bridge.md`、`rounds/RELEASES.tsv`、`DEV_GUIDE.md`、
  `release_body.md` / `release_body_neo.md` / `RELEASE_NOTES_v1.3.2.md` / `store/release_notes_1.3.2.md`、
  `机制详解.md`。

## 验证

- 两树 `javac EXITCODE=0`。
- 14 护栏全 `FAIL 0`。
- `faceToward` 调用点计数两树逐文件一致（`BlockWalkOn` 3 / `BridgeUpBehavior` 3 / 挖矿 2 / 伐木 2）。
- 包体：forge **17,773,353** 字节 `3e85f305…24188c`；neo **17,821,212** 字节 `190cb2c9…6251de`。
- 部署：`deploy_all.py` 4 个目标全 `match=True`（`elevated rc: 0`）。
- 回归：`test_server.py` / `test_maid_load.py` 两加载器各两件，四件全 `verdict: PASS`。

## 验收

- 伐木/挖矿里需要搭路（断崖 / 跨水 / 下行台阶）时，她**面朝要垫的那一格**垫块并走过去，
  不再出现头朝反方向的「倒着搭、倒着走」。
- 空中铺桥（搭路行为）与挖矿/伐木的桥块、台阶四条路径表现一致。
- 直上直下垫高（脚下原地垫块）不受影响——朝向不变、照旧起跳站上新方块。

## 边界

- 只在**直接施速的那一小段**（垫块后走上去的 12 tick 窗口）写朝向；走导航的常规接近仍是
  TLM 自己的转身逻辑说了算，二者不冲突。
- 朝向按「水平目标格中心」计算；水平位移小于 0.01 格（纯垂直垫块）时不动朝向。
- 不写 `setXRot`（俯仰）：搭路过程中不抬头低头，避免与垫块/挖掘的视线逻辑打架。
- 无论是否骑乘 / 坐下都生效（这两态下搭块本身已被 `MaidPlaceGuard` 拦掉）。

## 台账

- `rounds/RELEASES.tsv` 两条 1.3.2 行 **第一百一十四次**同号重打，旧字节
  `b584002f…df3d62`（forge）/ `f7793059…a40e19`（neo）（第一百一十三次 R977）作废。
- `DEV_GUIDE.md` 发布区间 → 八百五十九 ~ 九百七十八。
- 四份发布文案在 R977 条目之后追加本轮条目。
- `机制详解.md`：`BlockWalkOn` 一条新增说明；`MaidMineBehavior` / `MaidWoodBehavior` /
  `BridgeUpBehavior` 三条补「垫块前先转身」。
