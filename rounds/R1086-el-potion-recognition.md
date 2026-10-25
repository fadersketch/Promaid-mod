# R1086 1.20.1 面板识别神秘遗物药水

## 排班

- **跑道**：C 功能（仅 Forge 1.20.1；神秘遗物没有对应的 1.21.1 版本，Neo 树不动）。
- **开工前读过**：`MaidSupportPolicy`（候选/分类/记账键/门禁）、`PromaidConfigScreen`
  的支援品候选枚举与 `supportStackOf`、`MaidAidOwnerBehavior`（主人/姐妹/友军
  支援链）、`SelfPreservationBehavior`（自喝与自投）、神秘遗物 2.30.1 的 javap
  （`ItemBasePotion` / `UltimatePotionBase` / `PotionHelper` / `AdvancedPotion` /
  `EnigmaticPotions`）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 摸清数据存法 | 药水 id 在 NBT `EnigmaticPotion`，效果表按 id 解析 | ☑ |
| 1 | 新软联动类 | 未装神秘遗物时恒假，不增加原版路径开销 | ☑ |
| 2 | 判据与效果表统一 | `potionItem` / `potionId` / `effectsOf` / 分类推断认进阶药水 | ☑ |
| 3 | 面板候选与记账键 | 三个形态 × 全部进阶药水各一行，键可还原 | ☑ |
| 4 | 使用链接线 | 饮用/投掷、援助链、自保链都能选中并生效 | ☑ |
| 5 | 编译与护栏 | Forge `EXITCODE=0`；14 护栏 `FAIL 0` | ☑ |
| 6 | 重打、部署、测试 | 4 实例 `match=True`；四套测试 PASS | ☑ |

## 需求 / 来源

玩家反馈：1.20.1 的女仆配置面板无法识别神秘遗物（Enigmatic Legacy）的药水。

## 评估

### 一、为什么原版口径认不出

面板与支援策略此前只有两路口径：

1. **物品类**：`PotionItem` / `SplashPotionItem` / `LingeringPotionItem`；
2. **药水对象**：`PotionUtils.getPotion(stack)` + `ForgeRegistries.POTIONS`。

神秘遗物 2.30.1 的进阶药水两条都不满足（javap 实证）：

- `common_potion` / `ultimate_potion` 及其 splash/lingering 形态继承
  `ItemBase`，不是原版药水类；
- 药水 id 存在 NBT 字符串 `EnigmaticPotion`，效果表由
  `PotionHelper.getEffects(stack)` 按 id 从 `EnigmaticPotions.ULTIMATE_POTIONS /
  COMMON_POTIONS` 查出；
- `PotionUtils.getPotion(stack)` 对它们只会返回 `minecraft:empty`，
  所以按注册名的所有判定都落空。

结果：支援物品分类页既扫不到这些物品，也无法按效果自动分类。

### 二、软联动口径

新增 `com/maidsmart/compat/EnigmaticLegacyCompat`：

- 先用 `ModList.get().isLoaded("enigmaticlegacy")` 在场判断；
- 反射只取一次：`PotionHelper.getEffects(ItemStack)`、
  `EnigmaticPotions.ULTIMATE_POTIONS / COMMON_POTIONS`、`AdvancedPotion.getId()`；
- 药水 id 直接读写 NBT `EnigmaticPotion`（`CompoundTag.m_128425_(key, 8)` 判在不在，
  `m_128461_` 读、`m_128359_` 写）；
- 形态按物品类简单名判定：`UltimatePotionBase` = 饮用，
  `UltimatePotionSplash` / `UltimatePotionLingering` = 喷溅/滞留。

没装神秘遗物时整类是恒假判断；装了但版本方法名变了也只是反射初始化失败、
退回旧行为，不影响原版药水。

### 三、接线点

**判据层（`MaidSupportPolicy`）**

- `potionItem` 追加进阶药水；
- `potionId` 返回 `enigmaticlegacy:<id>`（与记账键 `物品id|药水id` 对齐）；
- 新增 `effectsOf`：注册表效果 + `CustomPotionEffects` + 进阶药水效果，三者合并；
- `derive` / `harmfulToUndead` / `uselessToUndead` 改走 `effectsOf` / `hasEffect`；
- 新增 `drinkable` / `throwable` / `splashPotion` / `effectKey` 四个形态与 CD 口径，
  同效果不同形态、`long_/strong_/ultimate_` 前缀归一；
- 默认推断表补 `haste`、`invisibility` 到「增益」（进阶药水的迅捷/隐身系列）。

**面板（`PromaidConfigScreen`）**

- 支援品候选在「注册药水 × 三形态」之后追加「进阶药水 id × 三形态」：
  `common_potion` / `ultimate_potion` 及 splash/lingering 各一行，空药水跳过；
- 记账键还原：先查原版药水注册表，查不到再按进阶药水 id 写回 NBT；
- 悬停行展示 `enigmaticlegacy:<id>` 的 id 段，搜索框按效果名也能命中。

**使用链（`MaidAidOwnerBehavior` / `SelfPreservationBehavior`）**

- `potionEffectsOf` 统一委托 `MaidSupportPolicy.effectsOf`，投掷/云/自喝读到的
  效果一致；
- 饮用型判定由 `instanceof PotionItem` 改为 `drinkable`；投掷型由
  `Splash/Lingering` 改为 `throwable`；
- 援助链的「直接喂姐妹/友军喝」走物品自己的 `finishUsingItem`：
  原版药水对非玩家返回原栈（补一个空瓶），进阶药水会自己返回空瓶——
  容器返还改成分支，避免一次喝药返还两个瓶子；
- 自保链的抗火/治疗/情境药水判定改按效果认；
  进阶药水没有原版 `Potion` 对象，投掷云改为逐条 `addEffect` + 按效果算颜色；
- 治疗量：原版注册名认不出时按 `instant_health` 等级折算 `4 << 等级`。

## 改动文件

- `promaid_src/com/maidsmart/compat/EnigmaticLegacyCompat.java`（新增）
- `promaid_src/com/maidsmart/combat/MaidSupportPolicy.java`
- `promaid_src/com/maidsmart/combat/MaidAidOwnerBehavior.java`
- `promaid_src/com/maidsmart/combat/SelfPreservationBehavior.java`
- `promaid_src/com/maidsmart/config/PromaidConfigScreen.java`
- `tools/verify/check_reflection.py`（Forge 基线：`Class.forName` 87→89、
  `getMethod` 207→209）

## 验证

- Forge 编译 `EXITCODE=0`；Neo 树无改动（jar 与 R1085 逐字节一致）。
- 14 护栏：`FAIL 0 / WARN 0`。
- 专服回归：`test_server.py 1201` PASS、`test_maid_load.py 1201` PASS、
  `test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS。
- Forge jar：18,134,483 字节，SHA256
  `666D444136C7E2E8D4799648E3FA08DC22BBD64DB95167617286AC4B428C75B3`。
- Neo jar：18,194,329 字节，SHA256
  `6119934D727890E51B37A013830932FF696F814F873CFDDF394DCE0661DCF692`。
- 已部署 4 实例，`match=True`：1.21.1 Neo、1.20.1 Forge、龙之冒险、pack1201。

## 边界

- 只处理神秘遗物**进阶药水**（`EnigmaticPotion` NBT 在场）。召回/救赎/扭曲/虫洞
  这类效用药水不带 NBT、也没有可分类的效果，仍不进支援表。
- 进阶药水的**喷溅/滞留**不做原版投掷实体，沿用本模组「触发即生效」口径：
  直接对目标施加效果；滞留形态走直伤分支，不生成效果云。
- 未装神秘遗物时不加载任何反射；装了但反射失败时所有新判据返回假，
  原版药水路径与旧版一致。
- 面板的默认分类仍由效果推断，玩家可在表里改成对策/增益/回复/禁用。

## 验收

1. 1.20.1 装神秘遗物，打开 战斗与自保 → 贴身辅助 → 支援物品分类。
   - 搜索 `healing` / `ultimate` / `fire`，能看到 `enigmaticlegacy:ultimate_potion`
     等行，图标与名称正常，分类标记按效果显示。
2. 把「终极治疗药水」标成回复、把「终极抗火药水」标成对策，交给会支援的女仆。
   - 主人低血/着火时她会正常取用；喝一瓶只消耗一瓶、只返还一个空瓶。
3. 女仆自保时背包里有终极抗火/治疗药水：掉岩浆或低血会正常喝/投。
4. 卸载神秘遗物后打开同一面板：原版药水列表与分类行为不变。
