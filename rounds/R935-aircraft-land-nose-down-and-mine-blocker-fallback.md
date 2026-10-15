# 实测九百三十五 固定翼降落「先下压 · 缩短」＋挖矿「朝挡路块走不到 → 改走接近链」

> 跑道：**开发者点名三件**。开发者原话：「1.女仆现在乘坐滑翔式飞机可以降落了，但是降落花费的时间太长，并且中途还会飞离玩家很长一段距离，最终才能成功降落。我想的是降落需要先将运动的方向下压。尽可能的缩短降落所需要的时间。2. 13:47，女仆旁边明明没有任何的阻碍，包括头顶，但是女仆仍然什么都不做。3.女仆在驾驶汤姆f6f的时候仍然没有办法降落。」
> 上一轮：`rounds/R934-aircraft-land-hard-speed-limit-and-mine-blocker-digupcap.md`（九百三十四 给降落加"硬限速、逐渐降为零"＋挖矿补"搭不上就挖开头顶"）。
> 本轮依据的实机日志 = 开发者本次测试（NeoForge_21.1.250 `latest.log`，13:55~14:01，**跑的就是九百三十四 的 jar**）。

## 排班

- **本轮主项**：①按开发者口径把降落改成"**先压低方向**、尽可能缩短"；②挖矿"旁边没阻碍却什么都不做"——先用日志钉死形状再补动作；③汤姆6 落不下来——与 ① 同一条链路，一并交代清楚。功能面（数值/矿表/建筑/对话/战斗）一律不动；版本号仍 **1.3.2**。
- 步骤：改 Forge → 镜像 Neo（同一份模板 + 名字映射，逐锚点断言唯一）→ 两树 `javac EXITCODE=0` → 全护栏 `FAIL 0` → 打 jar → 部署 4 实例 `match=True`。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`，并给逐条看日志的方法）。

## 需求 / 来源

- 现象①「可以降落了，但降落花的时间太长、中途还飞离玩家很远」：本轮日志实证——13:58:24 点 home，13:58:48 才接地，**整整 24 秒**；其中 13:58:24~13:58:38 那 **14 秒**她在"进圈"里从**距主人 184 格飞到 224 格**再绕回来。之后 13:58:38 定跑道，又因"离地 44.5 格 > PATTERN_ALT(24)+TOL(10)"转了 3 秒 `盘旋（绕圈降高度）` 才进 `进场`。开发者要的口径很明确：**先把运动方向下压**（立刻降高度），把这段绕圈/等待省掉。
- 现象②「旁边明明没有任何阻碍（含头顶），女仆仍然什么都不做」：日志里 13:57:01~13:57:53 的 **52 秒**就是真身（见 `## 定位与改法 ②`）——她顶着 `朝挡路块推进(够不着矿) … path=false`，周围空着却一步不动。开发者说"原因未知"，本轮把原因写死并补上动作。
- 现象③「汤姆6 仍然没有办法降落」：日志里 14:01:21 汤姆6 进了同一条降落链路（`定跑道` → `盘旋` → `进场`），但 `离地` 降到 23.8 格后就**一路保持**、`距接地点` 越飞越大（1→182）、`航向误差` 恒在 169°。与 ① 同源（高度被"进场段钉在 PATTERN_ALT"顶住、且要飞一段长外飞腿才能掉头），所以 ① 的改动对它同样生效。

## 定位与改法

### ① 固定翼降落：删掉"太高就盘旋降高度"，进场段也一路压机头往下（+ 更陡下滑道 / 更快下沉 / 更短外飞腿）

**定位**（两树，`MaidMountCompat.aircraftLandFinal`）：进近链路对"没对准 / 太高"的处理是

```java
if (cross > CORRIDOR || |hdgErr| > MAX_BEARING || aboveRunway > PATTERN_ALT + TOL) {
    ...
    if (aboveRunway > PATTERN_ALT + TOL) {
        holdAircraftPattern(mount, why, tom, patternY);   // ← 绕圈降高度，实机 3 秒
        return true;
    }
    ... 飞向"接地点外 OUT_D+OUT_LEG 格"的固定点重建距离 ... 高度钉在 patternY ...
}
```

也就是说：**只要还高，就一律先绕圈把高度降到"接地点上方 24 格"，再谈对准**；而且对准那一段的高度也被钉死在 `patternY`（不下降）。这是"降落要 24 秒"的主因。

**改法**（两树，同一份逻辑）：

- **删掉"太高 → 盘旋降高度"整段**（`holdAircraftPattern(...); return;` 那块连同 `patternY` 变量一起删）：太高也直接走下面的"飞向固定点重建距离"。
- **进场段的高度目标改成下滑道本身**（与进近段同一公式）：
  ```java
  double glideY = tdY + clamp(max(before,0), 0, GLIDE_MAX_D) * AIRCRAFT_LAND_GLIDE_SLOPE;
  double targetY = Math.max(glideY, aircraftLandFloor(mount));   // 地形净空线兜底
  double pdy = targetY - mount.getY();
  aircraftHoldAltitude(mount, pdy, pdy > 0.0 ? AIRCRAFT_VY_CRUISE_MAX : AIRCRAFT_LAND_DESCENT_VY_MAX);
  ```
  ⇒ 从点 home 那一拍起她就**一路压机头降高度**（这就是开发者要的"先将运动的方向下压"）。之所以现在敢这么改：九百三十四 已把速度硬钳到 0.80，"降率追不上航程、冲过接地点还在掉"那条老顾虑不再成立。
- **更陡的下滑道**：`AIRCRAFT_LAND_GLIDE_SLOPE` **0.10 → 0.18**（≈5.7° → ≈10.2°）——同样距离压得更低、更早接地。
- **更快的下沉**：`AIRCRAFT_LAND_DESCENT_VY_MAX` **0.20 → 0.30**（≈4 → ≈6 格/秒）。
- **更短的外飞腿**：`AIRCRAFT_LAND_OUT_LEG` **90 → 60**（重入瞄准点从"接地点外 180 格"缩到 150 格）。`AIRCRAFT_LAND_PLAN_KEEP_D` 是派生值，自动 268 → 238。
- 汤姆6 走同一条 `aircraftLandFinal`（`tom` 只是换控制通道），所以 ③ 由同一批改动覆盖。
- **没动**：条带选址与判据、`OUT_D`、`CORRIDOR` / `MAX_BEARING`、八向顺机头选跑道、`PATTERN_ALT`（仍作为"是否太高"的判据与进圈目标高度）、`PLAN_KEEP_TICKS`、接地段高度闸与 `killHorizontal`、`holdAircraftPattern` 本身（现在只在"挑不出跑道"时用）、九百三十四 的硬限速。

### ② 挖矿：`朝挡路块推进` 走不到时改走接近链（"旁边空着却不动"52 秒）

**取证**（`logs/latest.log` 13:57:01~13:57:53）：

```
13:57:01 work exit: … reason=mine 朝挡路块推进(够不着矿) blocker=(-1408,20,-670) target=(-1407,19,-669)
13:57:01 work exit: … reason=walk 沿用同一目标(导航在走) stand=(-1408,19,-670) feet=(-1412,20,-674)
13:57:03 work walk stuck: … target=(-1407,19,-669) stand=(-1408,19,-670) feet=(-1411,20,-674) dist=4 path=false -> stop+retarget
13:57:06 …（同样两行）  … 13:57:51 …（同样两行）
13:57:53 work walk stuck: …  ← 之后玩家收放救回
```

**52 秒**里她每一步都走"朝挡路块推进"这一条、脚下高度/位置基本不动、`path=false`、零破坏。挡路块 `(-1408,20,-670)` 与她的脚 `y=20` **同高**（所以既不进"头顶搭高"、也不进"挖开头顶"那两道闸），而"朝挡路块脚下走"这个落脚点**导航给不出路径** ⇒ 三条路全堵死 = 原地站着。

**改法**（两树，`MaidMineBehavior`）：在挡路块分支给"朝挡路块脚下走"**加一道判据**——`walkToBlockFace` 之后若导航**给不出路径**（`maid.getNavigation().isDone()` / Forge SRG `m_21573_().m_26571_()`），就**改走 `approachOre` 的接近链**（下沉 / 搭高 / 垫桥 / 挖开头顶都在那条链上），并留一行 `mine 挡路块走不到→改走接近链`；有路径时行为与旧版逐字一致（仅多一次 `isDone()` 查询）。

## 触及文件

- `promaid_src` / `promaid_src_neo` × `com/maidsmart/combat/MaidMountCompat.java`（① 删"太高→盘旋"整段 + 进场高度改下滑道 + 三个常量）
- `…/task/MaidMineBehavior.java`（② 挡路块分支"走不到→改走接近链"）
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行 sha256 + 第七十四次同号重打）
- `rounds/R935-aircraft-land-nose-down-and-mine-blocker-fallback.md`（本文件）

## 验证

- **两树编译**：`compile_promaid.bat` → `EXITCODE=0`；`compile_neo.bat` → `EXITCODE=0`。**首轮 Forge 编译失败 1 处**并**被打包脚本当场拦下**（`FATAL: …源比 class 新，拒绝打包旧字节码`）——`aircraftLandFinal` 里新写的 `AIRCRAFT_LAND_PATTERN.merge(...)` 沿用了 Neo 的 `mount.getUUID()`，Forge 应为 `mount.m_20148_()`；改正后重新编译 `EXITCODE=0` 再打包。
- **镜像自查**：`aircraftGovernSpeedHard`（九百三十四）与 ① ① 新增的 `glideY/targetY` 逐行读回两树——Forge 侧一律 SRG（`m_20186_()` / `m_20148_()`），Neo 侧一律 Mojang（`getY()` / `getUUID()`）；② 的 `isDone()` 两树分别 `getNavigation().isDone()` 与 `m_21573_().m_26571_()`。
- **全护栏**：11 个全 `FAIL 0`（`check_comment_style` **PASS** 931 文件 / 0 处；`check_preset` DIY 基线仍 **172/176**——本轮不增删开关；`check_workflow` 3 条历史 WARN）。`check_arch` 一致差 0。
- **两个 jar**：forge **17584846** 字节（sha256 `279EE195…86FE19`）；neoforge **17621197** 字节（`ECF20BCA…B3EE6F7B`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201）；旧包备份在 `patched/backup_old/`。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行 sha256 更新为新字节，并追加「第七十四次同号重打」；九百三十四 的旧字节 `B5CA0E3A…0170BC`（forge）/ `8F625A2F…A1C30B`（neo）作废。
- **未跑实机回归**（已部署，等开发者实测）。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- ① **降落变快、少飞远**：让她开固定翼（卓越前线 AIRCRAFT / Tom F6F / AC-130H 一类）在离主人几十格以上进 home 档。**判据**：
  1. 不再出现"`固定翼降落·盘旋（…绕圈等…）` 连着两三秒"那一段——太高时应当**直接**进 `进场`；
  2. `固定翼降落·进场 … 离地=…` 应当**一路往下掉**（不再钉在 ~24 格不动）；
  3. `固定翼降落·进近 … 限速=…` 随 `距接地点` 变小而降（九百三十四 的 `限速=` 读数），最后 `接地段` → `完成`；
  4. **总时长应当比之前的 24 秒明显短**；`距主人` 不该再涨到 200+ 才回来（外飞腿已从 180 缩到 150 格）。
  - 若她**压得太狠、擦地/撞地形**（日志里出现 `坠机` 或她忽然"完成"在很怪的坡上），请把那几行贴回来——把 `GLIDE_SLOPE` 调小（0.18 → 0.14）或把 `DESCENT_VY_MAX` 调回 0.24 即可。
- ② **挖矿不再"空着不动"**：给她挖矿任务。**判据**：一旦出现 `mine 朝挡路块推进(够不着矿) … path=false` 这类停滞，本轮之后应当**紧跟**出现
  `mine 挡路块走不到→改走接近链 blocker=… target=…`（她换去走接近链了），随后接 `mine 头顶压住挖开` / `mine 朝挡路块搭高` / `mine 挖掉一块` 之类的真动作；**不该**再出现"同一条 `朝挡路块推进` + `work walk stuck` 连着几十秒一字不变"。若仍如此，请把当时前后 20 行贴回来。
- ③ **汤姆6 降落**：让她开汤姆6（TOM6）进 home 档。**判据**：同 ①——不该再"进了 `盘旋`/`进场` 后一直保持 ~24 格离地、`距接地点` 一路涨"。若仍复现，请把 `卓越前线载具(TOM6)` 那几行整段贴回来（尤其 `进场 … 航向误差=` 是否一直卡在同一个大角度）。
- 判定：逐条按上面现象观察即可。

## 边界（没动什么）

- **没动**：降落链路的条带选址/判据、`OUT_D`、`CORRIDOR`（14）/ `MAX_BEARING`（40）、八向顺机头选跑道（`ALIGN_DEG`）、`PATTERN_ALT`（24，仍作"太高"判据与进圈高度）、`PLAN_KEEP_TICKS`（6000）、接地段高度闸（`FLARE_ALT` / `TOUCH_D`）与 `killHorizontal`、`holdAircraftPattern` 本身（现在只在"挑不出跑道"时调用）、九百三十四 的 `AIRCRAFT_LAND_SPEED_STEP` / `aircraftGovernSpeedHard`；挖矿的 `approachOre`（含既有 `digUpCap`）、`pillarUpStep`、`walkToBlockFace`、`walkToOreBase`、`MINE_WEDGE_TICKS`、`RECENT_DISCARD`、`blockedOres` 全都不变。
- **口径变更（有意，非回归）**：① 降落"太高"不再盘旋降高度（删该分支），进场段高度目标由 `PATTERN_ALT` 改为下滑道；`GLIDE_SLOPE` 0.10→0.18、`DESCENT_VY_MAX` 0.20→0.30、`OUT_LEG` 90→60（`PLAN_KEEP_D` 派生 268→238）。② 挖矿"朝挡路块脚下走"无路时改走 `approachOre`。
- **没新增开关**；DIY 关闭条数基线仍 **172/176**；版本号仍 **1.3.2**（`WORKFLOW.md` §5「验收前不升号」）。新增常量：无（只改既有三个常量的值）。
- 临时脚本与日志（`tools/_r935_*.py`、`tools/_chk.py`、`_cur.txt` / `_c1.log` / `_c2.log` / `_g.log` / `_b1.log` / `_b2.log` / `_d.log`）**已删**；仓库根目录既有的 `_*` 文件与预置目录未动。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第七十四次同号重打。
- `DEBT.md`：不新增条目——① 是"按开发者口径把降落改成先下压/缩短"（常量调整 + 删一段等待），② 是"给走不到的那条路补一个动作"，都不引入新的未验证功能面。
