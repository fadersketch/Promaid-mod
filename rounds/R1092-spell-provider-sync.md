# R1092 万法皆通 1.9.x 新魔法同步

## 排班

- **跑道**：第三方兼容同步（两树镜像），R1091 的顺延。
- **为什么这轮先做兼容**：用户把《车万女仆：万法皆通》更新到新版本（Forge 1.9.3 /
  Neo 1.9.1），新增了若干法术前置；需要确认空袭调用的附属 API 有没有变，以及
  我们这边要不要跟着同步。
- **开工前读过**：`MaidSpellCastCompat`（空袭施法链路）、`MaidSpellCompat`
  （"她会不会法术"的判定）、附属 1.9.3（Forge 1.20.1）与 1.9.1（Neo 1.21.1）
  两个 jar 的字节码。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 核对空袭用到的附属 API | 方法签名逐一对上，无删除/改签名 | ☑ |
| 2 | 数据兜底表补齐新 provider | 两平台 provider 表的并集全部覆盖 | ☑ |
| 3 | 启动日志列出已认到的前置 | 日志能看到 provider 名单 | ☑ |
| 4 | 编译、护栏、重打、部署、测试 | 两树 `EXITCODE=0`；14 护栏 `FAIL 0`；四套测试 PASS | ☑ |

## 一、会不会受影响

**不受影响。** 空袭走的是附属的公开 API，本轮逐方法核对（javap）：

- `SpellBookManager#getOrCreateManager(EntityMaid)`、`#getProviders()`、
  `#stopAllCasting(...)`；
- `ISpellBookProvider#setTarget(EntityMaid, LivingEntity)`、`#castSpell(EntityMaid)`、
  `#isCasting(EntityMaid)`；
- `IMaidSpellData#getSpellBooks()`。

这些方法在 1.9.3（Forge）和 1.9.1（Neo）里名字、参数、返回类型全部保持原样。

## 二、新魔法的同步方式

空袭的通用施法**不枚举具体法术**：它只把目标同步给每个 provider，然后调
`castSpell`，由附属自己从女仆所有法术书里挑一个可用的。因此附属新增 provider /
法术时，我们的施法链路**自动跟随**，不需要按法术逐个登记。

真正滞后的是"她会不会法术"的数据兜底：旧版只写了铁魔法与新生魔艺两个数据类，
其余 provider 的书如果被收进末影口袋等特殊位置，我们的判定会漏掉。现在把表补齐为
两平台 provider 表的并集（元素 = provider id + 数据类）：

| provider | 数据类 | Forge 1.9.3 | Neo 1.9.1 |
|---|---|---|---|
| irons_spellbooks | MaidIronsSpellData | 有 | 有 |
| ars_nouveau | MaidArsNouveauSpellData | 有 | 有 |
| psi | MaidPsiSpellData | 有 | 有 |
| goety | MaidGoetySpellData | 有 | 有 |
| usefulmagic | MaidUsefulMagicSpellData | — | 有 |
| mna | MaidManaAndArtificeSpellData | 有 | — |
| ebwizardry | MaidWizardrySpellData | 有 | — |
| youkaishomecoming | MaidYHSpellData | 有 | 有 |
| slashblade | MaidSlashBladeData | 有 | 有 |

本平台不存在的类在解析时得到 `null`，循环直接跳过，不影响其余 provider。
忽略表（默认拔刀剑 / 夜雀）里的 provider 在数据兜底里也一并跳过，与"随身法术装备"
的口径保持一致。

另外在解析成功时记一行日志「法术前置已就绪：…」，新版本增删前置时可一眼确认
我们和附属看到的是同一批。

## 改动文件

- `promaid_src/com/maidsmart/combat/MaidSpellCompat.java`
- `promaid_src_neo/com/maidsmart/combat/MaidSpellCompat.java`

## 验证

- Forge / Neo 编译均 `EXITCODE=0`。
- 14 护栏：`FAIL 0 / WARN 0`（注入点 285）。
- 专服回归：`test_server.py 1201` PASS、`test_maid_load.py 1201` PASS、
  `test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS。
- Forge jar：18,137,526 字节，SHA256
  `F5B07C63969617238F77D86A614C3680F2A6F12464974F2CDBAF1835BE0150BF`。
- Neo jar：18,197,300 字节，SHA256
  `BEACD05C2FCDCCF5DF8FDA66C75D640A2D5623934449F01E3EC7DD7AAAA93A04`。
- 已部署 4 实例，`match=True`。

## 边界

- **位移法术表仍是铁魔法专用**：`DEFAULT_CLIMB_SPELLS` / `DEFAULT_BOOST_SPELLS`
  里的法术 id 与 `findDashSpell` 读的槽位都来自铁魔法；其他 provider 的位移类法术
  不会自动用于"起飞/冲刺"。通用施法（空袭顺带放法术）不受此限。需要时可在配置里
  手动补候选 id，或后续再为其他 provider 适配位移读取。
- 附属不在场 / 版本再次改包名：反射解析失败只会让数据兜底返回 false，装备判定
  （`hasSpellGear`）仍然有效，空袭施法层也可独立降级，不会崩。
- 新增 provider 的"忽略/武器"归类沿用现有配置 `combat.spellGearIgnore`；本轮的
  默认忽略项没有变化。

## 验收

1. Forge 装 1.9.3 + Goety：启动日志出现「法术前置已就绪」，名单含 goety。
2. 女仆带着 Goety 聚晶（不放在主手）：能被识别为"会用法术"，可进法术战斗候选。
3. 空袭中顺带施法照常触发；附属新支持的 provider 法术无需改配置即可参与随机挑选。
4. 不装万法皆通：所有判定与旧版一致，无新增日志噪音。
