# R992 三项：扔的炸药兼容 TNT 矿车 / 拟真降落开关 + 盘旋加高默认 60 / 矿表木材表默认带标签行

> 上一轮：`rounds/R991-selfwings-bauble-dual-condition.md`（自推鞘翅判定放宽）。
> ⑭ 是开发者在 ⑫⑬ 构建途中追加的同一批需求，两件在同一只 jar 里交付（RELEASES 一条行对覆盖 998~999）。

## 排班

- **⑫ 投掷链兼容 TNT 矿车**：女仆"掏出炸药"时认到 TNT 矿车，扔出去的就是**它自己那一辆点着的矿车**（不再回落成一枚普通引信 TNT）；弹道按矿车物理另算；守卫（友伤/地形）按登记表认账。
- **⑬ 卓越前线两个配置**（都进「物品 → 骑乘指挥棒」页）：① 新开关「拟真降落」（默认关 = **上一个版本的降落方式**，开 = 上一版之后的拟真链路）；② 「滑翔类盘旋加高」的开关**找不到 → 搬到骑乘指挥棒页**，默认 **30 → 60 格**（老档一次性迁移）。
- **⑭ 矿表 / 木材表默认带标签行**：默认名单末尾带 `#forge:ores` / `#c:ores` / `#minecraft:logs` / `#minecraft:bamboo_blocks` 四条**标签行**——带标签的模组矿 / 模组原木开箱即算；面板绿勾按"标签行覆盖"口径；把被标签盖着的方块点小叉移出 = 该标签行自动展开成逐块行（这一块除外），"移出精确到那一块"；老档一次性补这四行。
- 完成判据：两树 `javac` `EXITCODE=0`；14 护栏 `FAIL 0`；双 jar、4 实例部署 `match=True`；专服回归三件 `PASS`。
- 谁验收：开发者（本轮已部署 + 回归；`## 验收` 记 `未验收`，等实机复看）。

## 需求 / 来源

- ⑫：开发者需求——扔炸药要能兼容 TNT 矿车这类东西（"可以偷出 TNT 矿车"）。
- ⑬：开发者需求——骑乘指挥棒里再加两个配置：① 是否拟真降落（默认关，关 = 回到上个版本的降落方式）；② 滑翔翼飞机的飞行盘旋高度（"目前的配置好像是玩家头顶 30 格，但是我没有找到这个开关在哪里，默认应为 60 格"）。
- ⑭：开发者需求——挖矿与伐木的默认配置内要勾选"带有矿物 / log 标签"的那些方块（开箱即认模组矿 / 模组原木）。

## 定位与改法（两树镜像）

### ⑫ TNT 矿车进出投掷链

- **javap 实证**（1.20.1 SRG / 1.21.1 Mojmap 两版）：`MinecartTNT` 只有 `(Level,double,double,double)` 构造器与 `primeFuse()`（固定 80 tick）；私有 `fuse` 字段（`f_38647_` / `fuse`）**没有公开写口**；tick 里引信递减到 0 炸、另有"水平碰撞且水平速度 ≥ 0.1"即炸；威力 = `4.0 + rand×1.5×min(5, 水平速度)`。
- **改法**：`BombItems.isTntMinecart`（原版 id 或"矿车物品 + 注册名带 tnt"）；`BombThrow` 认到矿车 → 放**真的 `MinecartTNT`**（不回落成 `PrimedTnt`），弹道用矿车物理的闭式解（重力 -0.04、落地反弹、0.95 空气阻力）另算 `flightTicksCart` / `requiredVyCart`；引信用反射直写 `fuse`（两树字段名都试，写不进退回 `primeFuse()` 并在日志注明）；放出即 `MaidTntBlastGuard.rememberTnt` 登记（地形控制"不破坏方块"与主人 / 友军免伤的归因都走这一张表）。
- `MaidTntBlastGuard.maidOfTnt` 扩口：只认**登记过的** `MinecartTNT`（别人的矿车一律不认，绝不对别人的爆炸动手）；MOD 自己的 `PrimedTnt` 链路一字未动。

### ⑬ 拟真降落开关 + 盘旋加高归位

- **上一个版本的降落链路原样恢复**：从 git HEAD（v1.3.1）按方法体括号匹配整段搬回，改名 `aircraftHomeLandingLegacy` / `landingSpotLegacy`；仅两个**后来被拟真链路改过值**的常量与两张缓存表改名（`AIRCRAFT_LAND_DESCENT_VY_MAX_LEGACY` = 0.20、`AIRCRAFT_LAND_SPOT_TTL_LEGACY` = 100、`AIRCRAFT_LAND_SPOT_LEGACY` / `AIRCRAFT_LAND_SPOT_AT_LEGACY`），日志带「·旧式」；`resetAircraftLanding` / `endAircraftLanding` 两处退出路径把两张旧表一并清。分发点只有一处：`aircraftHomeLanding` 开头 `if (!realisticLanding()) return aircraftHomeLandingLegacy(...)`。
- **新开关**：`COMBAT_RIDE_REALISTIC_LANDING`（`ride.realisticLanding`，默认 false），面板行在「物品 → 骑乘指挥棒」页；语言键两树四份。
- **盘旋加高**：`ride.fixedWingAltBonus` 默认 30 → 60（配置默认值、兜底常量 `AIRCRAFT_ALT_BONUS`、面板行文字与声明 javadoc 同步）；面板行从「空袭数值」页**移出**到「骑乘指挥棒」页（原先"找不到开关"的根因）；**老档一次性迁移**：还停在旧默认 30.0 的档搬 60.0（`FIXEDWING_ALT_60_MIGRATED`，与 TNT 追踪 10→20、悬停高度 15→3 同款判据）。

### ⑭ 矿表 / 木材表默认带标签行

- **表语法扩一条**：`#命名空间:标签=价值`（标签行）——`MaidMineBehavior.blockIdsInTag`（注册表 tag 视图，两树各用各的 API：`BlockTags.create` + `m_206058_` / `TagKey.create` + `getTagOrEmpty`）展开成逐块；`loadCustomOres` / `loadCustomWoods` 命中标签行时整条进表（同价）；排班软探测早已收敛到 `isTargetOre` / `isTargetWood`，一行不动自动跟随。
- **默认表**：矿表尾 `#forge:ores=300` / `#c:ores=300`；木材表尾 `#minecraft:logs=300` / `#minecraft:bamboo_blocks=300`（不存在的标签恒不命中，安全）。老档一次性补这四行（`MINE_WOOD_TAGLINES_MIGRATED`；表里已有任何 `#` 行的档一概不动）。
- **面板口径**：`isInList` / `getOreValue` 认"标签行覆盖"（模组矿图标默认绿勾；覆盖集合按标签行集合做缓存，网格每帧每格只是一次 Set 查询）；`removeOre`（小叉）先把标签行**展开成逐块行（这一块除外）**再走原删除——"移出"精确到那一块，同时不给"取消勾选不生效"复活的机会；名单列表里标签行显示成「[标签] #forge:ores」，删这一行 = 整条标签不再算。
- **文案**：两处 `.comment()`（矿表 / 木材表）、两章手册开场、四份 lang、`assets/promaid/i18n/en_us.json` 三条长文本键（木材表说明 / 挖矿开场 / 伐木开场）同步改写。

## 触及文件

- `com/maidsmart/combat/BombItems.java` · `BombThrow.java` · `MaidTntBlastGuard.java`
- `com/maidsmart/combat/MaidMountCompat.java`（Legacy 链路 + 分发 + 默认常量）
- `com/maidsmart/config/MaidSmartConfig.java`（新开关 / 默认值 / 两条迁移标记 / 文案）· `PromaidConfigScreen.java`（两行归位 + 标签口径）
- `com/maidsmart/task/MaidMineBehavior.java` · `MaidWoodBehavior.java`（标签行）
- `com/maidsmart/ProMaidMod.java`（两条迁移）· `com/maidsmart/guide/GuideChaptersWork.java`
- `assets/maid_smart/lang/{zh_cn,en_us}.json` · `assets/promaid/i18n/en_us.json` · `assets/promaid/panel_coverage.txt`
- `tools/verify/check_reflection.py`（getDeclaredField 基线 7→8 两树，见下）· `ARCHITECTURE-SWITCHES.md`（重生成）
- 以上各件的 Neo 镜像（`promaid_src_neo/...`）
- `rounds/RELEASES.tsv`（两条 1.3.2 `workspace` 行 + 第一百二十九次同号重打）· `DEV_GUIDE.md` §9

## 验证

- **两树编译**：`compile_promaid.bat` → `EXITCODE=0`；`compile_neo.bat` → `EXITCODE=0`。
- **全护栏**：14 项全 `FAIL 0 / WARN 0`（`check_comment_style` **PASS** 0 处；`check_preset` DIY 条数 108/111 与基线一致；`check_panel_coverage` / `check_i18n_keys` / `check_reflection` / `check_arch` / `check_guide_i18n` / `check_dev_voice` / `check_workflow` 全 0）。
- **两个 jar**：`patched/promaid-1.3.2-forge-1.20.1.jar` **17912978** 字节，sha256 `1607dc25…9bc675`；`patched/promaid-1.3.2-neoforge-1.21.1.jar` **17960579** 字节，sha256 `6b58cd3b…27e823`；`verify_jar_classes` OK、`MISSING: none`。
- **部署**：`tools/deploy/deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 / 1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201）。
- **专服回归三件**：`test_server.py 1201` **PASS**、`test_server.py neoforge1211` **PASS**、`test_maid_load.py` **PASS**。

## 验收

- 状态：**未验收**（已部署@2026-10-16：本机 4 实例与 `patched/` 逐字节一致；专服回归三件 PASS；未 commit、未 push）。
  - ⑫ 复现：给她 TNT 矿车 + 点火料、切战斗任务 → 她掏出矿车、朝敌人扔出**一辆点着的矿车**（日志里放出实体是 `minecraft:tnt_minecart`），撞到敌人 / 方块或引信到点都会炸；主人与同主女仆不会被这一炸伤到；「爆破不破坏方块」关着时她的矿车炸不掉方块。
  - ⑬ 复现：配置面板 → 物品 → 骑乘指挥棒 → 「拟真降落」开 / 关各降一次（关 = 上个版本"选平地压高度"那种，开 = 规划跑道进近）；同一页「滑翔类盘旋加高（格）」默认 60，老存档首启日志应有「滑翔类盘旋加高迁移：30 → 60」。
  - ⑭ 复现：删掉（或备份）`config/promaid-common.toml` 起一次 → 矿表 / 木材表末尾出现四条 `#` 标签行；面板矿表页里带矿物标签的模组矿默认绿勾；把其中一块点小叉 → 列表里那条标签行变成一串逐块行（该块不在其中），她不再挖那一块、其余照挖。
- 判定：三项各自按上面"看到什么"对照即可；任一不符请附 `logs/latest.log` 相关行或面板截图。

## 边界（没动什么）

- **版本号仍 1.3.2**（未升号，未 commit / push）。
- ⑫ 只碰"认到 TNT 矿车"这一支；普通 TNT / 重生锚 / 水晶 / 床各链路、追踪 / 静置 / 间隔各口径一字未动；未登记的矿车爆炸一律不认。
- ⑬ 拟真链路本体一字未动（新增的只是"关着时走哪条"的分发与新 Legacy 段）；两种降落方式共有的电量见底安全降落、接地清 crash / 停住不变；后触地两处打磨（936/938）**不在** Legacy 段内（忠实于上一个版本）。
- ⑭ 只扩了"表里多认一种行"：`!id` 旧行照旧被跳过、拉黑概念仍不存在；未登记的标签恒不命中；`mine.seekThroughWalls` 等扫描参数、连锁 / 掉落 / 播报全部不动；面板"移出"仍不需要黑名单（展开即精确移出）。
- **反射棘轮**：`check_reflection.py` 两树 `getDeclaredField` 基线 7 → 8（TNT 矿车引信直写，两版都没有写口；显式上调并留痕，见 TD-15）。
- 临时脚本（`tools/_p1.py` ~ `tools/_p4.py`、`tools/_g*.py`、`tools/_c*` / `_b*` / `_t*` / `_d*` / `_g*_out.txt`、`tools/_head_*.java`、`tools/_r*` 等）**已删**；仓库根目录 `_*` 仍恰为 5 项。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行（forge `1607dc25…9bc675` / neoforge `6b58cd3b…27e823`）列第一百二十九次同号重打；前一次 `9bf4ed97…9c01c2`（forge）/ `ba589066…1e8d95`（neo）作废。
- `DEV_GUIDE.md` §9：v1.3.2 实测区间 八百五十九 ~ 九百九十七 → **八百五十九 ~ 九百九十九**。
- `DEBT.md`：不新增条目（本轮无新增未清项；反射基线的显式上调在护栏文件内留痕）。
