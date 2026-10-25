# R1038 双树 着火取水视线判定（隔墙水源不再传送）（1.3.3 同号重打）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：A 修复（1.21.1 NeoForge + 1.20.1 Forge 同步）。
- **开工前读过**：`SelfPreservationBehavior` 着火分支（灭火器 → 水桶 → 找自然水 → 珍珠 → 抗火药水）、
  `findWater` / `findBlockBy`、2.5 格内直接 `moveTo` 入水的旧注释（v1.5.290）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位 | 确认 `findWater` 只按距离选水，2.5 格内直接传送，完全不看墙体 | ☑ |
| 1 | 视线判定 | 眼睛→水面中心的 `ClipContext` 射线不得命中实心方块 | ☑ |
| 2 | 身位判定 | 水上方一格必须是空气或水，避免传进 1 格水窝卡头 | ☑ |
| 3 | 双树镜像 | Neo 与 Forge 同一判据（SRG 改写），行为一致 | ☑ |
| 4 | 编译 | 两树 `EXITCODE=0` | ☑ |
| 5 | 护栏 | 14 护栏 `FAIL 0 / WARN 0` | ☑ |
| 6 | 专服回归 | Neo（挂 spearcore）与 Forge 的 `test_server` / `test_maid_load` 均 PASS | ☑ |
| 7 | 部署 | 双 jar 部署到 1.21.1 + 两个 1.20.1 实例（含 pack1201），字节数一致 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法见文末「验收」。

## 需求 / 来源

- 开发者原话：
  「这个灭火代码是不是有问题啊 女仆着火了回穿墙进到水里面然后给自己卡死，需要加视线阻拦。
  将这个改动同步到1.20.1」

## 评估

- 着火分支在灭火器/水桶都不可用时会 `findWater(maid)` 扫半径 12 的最近水源；旧 predicate 只判
  `minecraft:water`，不看中间有没有墙。选中后：
  - 水平距离 < 2.5 格 → 直接 `maid.moveTo(wx, waterY, wz, ...)` 传送进水；
  - 否则 → 导航到水面格。
- 传送本来就绕过了寻路，隔墙/隔地板的水会被直接选中 → 实机就是「穿墙进水窝，卡死」。
- 导航分支虽然不穿墙，但会朝一堵墙后面的水一直走，同样表现为贴墙卡住。
- R1038 给水源候选加两道判定：
  1. **视线**：从 `maid.getEyePosition()` 到水源格中心做
     `ClipContext.Block.COLLIDER + Fluid.NONE` 射线，必须 `MISS`；命中任何实心方块就跳过这格水。
  2. **身位**：水源上方一格必须是空气或水；实心天花板/地板直接跳过，避免传送进 1 格高的水窝。
- 判定放在 `findWater` 的 predicate 里，所以视线被挡的近处水会被跳过，继续找真正可见的水；
  全被挡住时自然落到珍珠/抗火药水/金苹果兜底，不会再穿墙。

## 改动文件

- `promaid_src_neo/com/maidsmart/combat/SelfPreservationBehavior.java`（4999 行）：
  `findWater` 增加 `waterAccessible`（视线 + 身位）。
- `promaid_src/com/maidsmart/combat/SelfPreservationBehavior.java`（4927 行）：
  Forge 侧按 SRG（`m_45547_` / `m_8055_` / `m_20299_(1.0F)` / `m_7494_`）镜像同一判定。
- `tools/arch/arch_modules.py` 与 `normify-promaid/`：Neo 行号与描述同步。

## 验证

- Neo 编译：`EXITCODE=0`；Forge 编译：`EXITCODE=0`。
- 全部护栏：`FAIL 0 / WARN 0`；`check_mixin_targets` 校验 277 个注入点。
- 专服：
  - Neo（临时挂载 `spearcore-1.21.1-neo-3.1.0.jar`）：`test_server.py neoforge1211` PASS；
    `test_maid_load.py neoforge1211` PASS；测试后已移除。
  - Forge：`test_server.py 1201` PASS；`test_maid_load.py 1201` PASS。
- Forge jar：18,056,603 字节，SHA256
  `BCC3F7F21D0B0EAA565DD4BD73D75582DA76F386E42703CA7CF5EA367FC24A82`。
- Neo jar：18,144,604 字节，SHA256
  `8A23C87450490DBFC5AFD3B0F1AB17370BA1CD66838F30546331294AE621B528`。
- 已部署：1.21.1 实例、1.20.1 Forge 实例、龙之冒险 1.20.1 实例、测试服 pack1201；
  四个目标均 `match=True`。

## 边界

- 只改「找自然水」这一条；灭火器、水桶原地放水、珍珠、抗火药水、金苹果链路不变。
- 视线射线使用碰撞箱（`COLLIDER`），玻璃/栅栏/台阶/铁栏杆都会挡住；树叶等无碰撞箱的方块不挡。
- 水源上方要求空气或水；1 格深水坑（上方空气）仍可用，2 格以上深水（上方水）也可用。
- 抬头取水（水在头顶上方）会被射线允许，但上方身位判定仍要求水上方非实心。
- 版本号仍为 1.3.3（同号重打）。

## 验收

1. **隔墙有水**：让女仆在石墙一侧着火，墙后 2 格是水池。她不应再穿墙进水；
   应在墙这边找其他水源，或走珍珠/抗火药水/金苹果兜底。
2. **隔地板有水**：站在封闭房间里着火，脚下/墙后是水。不应传送到地板下的水窝卡住。
3. **正常近水**：身边就是开放水池时，仍会正常入水灭火，不受影响。
4. **水桶优先**：包里有水桶且非下界时，仍先原地放水，不会因为找水判定改变而跳过水桶。
5. **两版本一致**：1.20.1 与 1.21.1 表现相同。
