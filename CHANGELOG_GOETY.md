# CHANGELOG_GOETY —— 诡厄巫法（Goety）兼容（本分支自用记录）

> 为什么单独一份：上游（fadersketch）明确要求**不要动主 CHANGELOG.md**、提交也不要带 changelog，
> 正文由他们收编；这份只是**本分支自用**的记录。
> 编号说明：上游的「实测」已经用到七百〇三，我们再用 704+ 会撞号，所以本文件用 **G 系编号**
> （G-1、G-2…，G = Goety）。

---

## G-1【第三种飞行：外部持续推进（Goety 飞行聚晶）】

### 起因（需求方口径）

> 「Goety 空战兼容……这个 mod 里面也有位移类法术，例如『发射聚晶』和『飞行聚晶』……
> 飞行聚晶则是让施法者保持某个固定的速度向视线朝向持续飞行。」
> 「如果我们把飞行聚晶做成第三种，那么我们是不是也能为其他持续推进型飞行铺平道路了？
> 但是这个飞行聚晶是固定的速度 v，喷气背包之类的是固定的加速度 a。」

所以本档的定位是**"外部持续推进"这个壳**：**推力由外部来源给**（飞行聚晶给恒速 v；
将来的喷气背包给恒加速 a），**我们只做瞄准 → 续放 → 到地方收手**，一个速度都不写。

### 事实基础（源码 + 1.21.1 字节码 + 无头实测）

| 项 | 值 |
|---|---|
| 法术 id | `goety:launch_focus`（发射聚晶）/ `goety:flying_focus`（飞行聚晶）；法杖 `goety:wind_staff`（威力 ×2） |
| 效果 | 每次施放 = `setDeltaMovement(视线方向 × d0)`，`d0 = power + potency/2` |
| power | 飞行 0.5（风杖 1.0）；发射 1.5（风杖 2.5） |
| 节奏 | 飞行 `Cooldown()=0` ⇒ **每 tick 覆盖**（真恒速）；发射走普通冷却 20 tick |
| 灵魂 | 走 Goety 给怪物留的正门 `Spell#mobSpellResult` ⇒ **不查灵魂、不进冷却**（Goety 自家怪物都这么放；灵魂扣费点只在 `DarkWand`，硬依赖 Player） |
| 特殊 | `SpellResult` 里有 `caster.fallDistance = 0` ⇒ **落体伤害在施法期间永远归零** |
| 包名 | 官方 NeoForge 版 `com.Polarice3.Goety`；万法皆通 1.21 分支对接的是 `za.co.infernos.goety` ⇒ 两套都试 |

### 无头实测（本工程第一次用真 Goety 跑通）

- **恒速验证**：每 tick 放一发 → 位置 (0.5, -60.000, 15.5→55.5)、速度恒为 (0, -0.000, **0.500**)、
  `NoGravity` 始终 false、着地 false；停下后立刻 (0, -0.078, 0.455)（重力 + 0.91 阻力）。
  ⇒ ①**恒速不衰减**；②**不掉高**（每 tick 覆盖压住重力，Goety 自己**不设** NoGravity）；
  ③**女仆的 travel/MoveControl 不会吃掉它**（这条原本是最大的未知，Goety 玩家版不用管、女仆是 Mob）。
- **巡航 → 下降 → 落地**：`goety_fly` 飞到 42 格外的点，4 秒到（≈10 格/秒），落在 (32.1, -59.99, 28.7)。
- **跟随 → 绕圈伴飞**：`goety_follow` 追上 28 格外的盔甲架后，围着它画半径 ~2.7 的圆，全程不着地。

### 过程中修掉的两个真问题

1. **到达判定漏了垂直** —— 只比水平距离时，"目标在头顶 20 格、她水平已进 2.2 格圈"会被判成到位
   ⇒ 直接进下降、看到贴地就收手（现场：飞往 (0.5,-40,0.5) 一秒后报「收手（落地）」）。现在水平与垂直都要到。
2. **推力飞行器卡在坑里** —— 落进 1 格深的坑/贴墙时，水平推力全被碰撞吃掉（位置一动不动、
   速度只剩重力、而法术其实在正常施放），永远出不来。现在判据「贴地 或 撞墙」时短暂抬头拔高
   （相当于给旋翼加总距），出坑自然恢复。

### 代码落点（**独立成档，不碰上游接手的 `MaidFreeFlightController`**）

- `com/maidsmart/goety/MaidGoetyCompat.java` —— 全反射软兼容层（识别法杖/聚晶、扫双手/背包/女仆饰品栏/Curios、放法术）
- `com/maidsmart/goety/MaidGoetyFlight.java` —— 第三种飞行的状态机（瞄准/续放/到点收手）
- `command/MaidGoetyFlyCommand.java` —— `/maid_smart goety_fly | goety_follow | goety_stop | goety_status`
- `command/MaidGoetyProbeCommand.java` —— 诊断探针（`goety_probe` 扫描 / `cast flying|launch N` 连放并记位置速度）

### 待办

- 灵魂口径：目前**免费无限飞**（因为走的是 mobSpellResult 这条不付费的正门）。
  要按 Goety 规则收费，得我们外层自己实现（女仆没有玩家那套 SE capability；可用她饰品栏里的
  **灵魂图腾 `goety:totem_of_souls`** 当钱包——需求方给的百科页讲的就是这个机制）。
- 风之魔杖（power 1.0 = 20 格/秒）下的手感还没实测：转向速度/绕圈半径可能要按威力缩放。
