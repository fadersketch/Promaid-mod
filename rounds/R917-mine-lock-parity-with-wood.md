# 实测九百一十七 挖矿锁定逻辑套用伐木（治「开了隔墙透视还是站着发呆」）

> 跑道：**A 修 bug**——P1（`DEBT.md` **BD-22**：挖矿选矿比伐木多三道「锁不住」的闸 →
> 开了隔墙透视仍选不出矿、站桩发呆）。承接 R916「挖矿套用伐木」的接近（approach）逻辑，
> 本轮补齐**选矿 / 锁定**这一半（上轮只对齐了走位）。

## 排班

- 开发者指令：「问题还是没有解决。开了隔墙透视。但是女仆还是会站在原地发呆不动。我觉得女仆锁定矿物的逻辑也可以套用伐木」
- 现场证据（本机 1.21.1-NeoForge 实例 `logs/latest.log`，2026-10-13 00:12–00:14）：
  - `mine behavior start` 反复出现（玩家收放魂符/重新召唤，实体 id 29→744→1504→1550→1562→1650）；
  - `mine stuck-reset: maid=... idle=355t pos=(-1468,73,-685)` 与 `idle=320t pos=(-1459,73,-694)`——两次发呆看门狗（约 16–18 秒原地不动）；
  - 聊天栏 `[大正女仆酒狐] 附近没有值得挖的矿石`（= `dialogue/WorkStatusReporter`，`targetPos` 为空的空转播报）；
  - 同一位置稍后又能挖到煤（`mine autoCollect ... pos=(-1468,78,-682)`）——说明矿一直在附近，是**选不出来**，不是不存在；
  - 配置确认：`mine.seekThroughWalls = true`、`breakBudget = 22`、`upRange = 24`、`downRange = 12`、`requirePick = true`、`repick = true`；`home = false`（跟随模式）。

## 定位：挖矿选矿比伐木多三道「锁不住」的闸（两树同款）

1. **`countBlocking`（穿透预算）把可开路方块也计入阻挡**：伐木侧放行 `isSoft` / `isOpenStone`
   （石头/泥土/深板岩/安山岩等"可挖穿的自然方块"不算障碍，只有箱子/机器/基岩 + 岩浆计数）；
   挖矿侧却在实测七十二被改成"逐层计入"，且射线按 0.5 格采样**不去重**（同一格被重复累加）。
   隔墙透视一开、入选的矿几乎全埋在石头里 → 预算被石头撑爆 → 一块也选不出
   （`mine.breakBudget=22` 看似很大，但被重复计数吃掉）。
2. **`ABANDONED`（本锚点永久排除表）——挖矿独有**：`abandon()` 与 `hardResetStuck()` 都把目标塞进
   这张表，只有 `relocate` / `slideAnchor` 换锚点才清；**每次发呆都会再啃掉一块**。伐木没有这张表
   → 伐木永远不会"越干越没目标"。
3. **`hardResetStuck` 丢目标 + 拉黑**：伐木的看门狗把「目标 + 超时时钟」一并保留（交给正常超时判
   够不够得着）；挖矿的把目标置空并写进上面那张永久表 → "发呆 → 黑名单 +1 → 再发呆"，直到框内
   矿全黑 → 报「附近没有值得挖的矿石」。

## 修法（两树镜像）

- `MaidMineBehavior.countBlocking`：`blocking++` 前加
  `if (isSoft(maid.getMainHandItem(), st) || this.isOpenStone(level, sample)) continue;`
  （与 `MaidWoodBehavior.countBlocking` 同口径；forge 侧 `maid.m_21205_()`）。
- **删除 `ABANDONED` 表**：字段 + `forget`/purge 簿记 + `fullScanOres`/`pickFromCache` 过滤 +
  `abandon` 写入 + `hardResetStuck` 保留与写入 + `relocate`/`slideAnchor` 清理，全部去掉。
  弃置只留 `abandonedPos`（下一次扫描跳过一次）+ `RECENT_DISCARD`（30 秒短排，伐木同款）。
- `hardResetStuck`：改为保留 `targetPos` + `TARGET_SINCE`（`Long keptSince`），不再置空、不再写黑名单
  ——与 `MaidWoodBehavior.hardResetStuck` 一字不差的口径。

## 验证

- 两树编译：`javac @compile_promaid.txt`（464 源）/ `javac @compile_neo.txt`（467 源）零错误。
- 全护栏：`check_comment_style` **PASS**（过程中修掉一处 11 行块——`abandon` 上方原有一段孤儿 javadoc
  与新增 javadoc 相邻，已删孤儿段）；`check_preset` / `check_catch_swallow` / `check_i18n_keys` /
  `check_dev_voice` / `check_comment_dup` / `check_reflection` / `check_arch` **FAIL 0 / WARN 0**；
  `check_workflow` **FAIL 0 / WARN 3**（历史遗留：PUBLISH_GUIDE 版本、1.2.4 归档哈希、13 个更早 jar 未登记）。
- 两个 jar：`verify_jar_classes` **OK**（mixin 包登记 Forge 97 / Neo 96；注入签名无歧义/错名；out↔jar
  class 双向一致）。
- 本轮未跑实机回归（先停机等你指令）。

### 部署（2026-10-13）

- 打 jar：`tools/build/build_promaid.py` → `patched/promaid-1.3.2-forge-1.20.1.jar`
  （**17579962** 字节，sha256 `d3a2d81a…445bc5`）；`build_promaid_neo.py` →
  `patched/promaid-1.3.2-neoforge-1.21.1.jar`（**17617993** 字节，sha256 `09c9031f…5326b`）。
- 装机：`tools/deploy/deploy_all.py` → 4 个本机实例全部 **`match=True`**（1.21.1-NeoForge_21.1.250 /
  1.20.1-Forge_47.4.10 / 龙之冒险：新征程v2.4a / mc_server_test pack1201；后两个实例需提权，脚本走 UAC
  后 rc=0）。旧包已备份到 `patched/backup_old/`（17618058 / 17580043，即 R916 第五十五次字节）。
- 留痕：`rounds/RELEASES.tsv` 两条 1.3.2 `workspace` 行的 sha256 列更新为新字节，并追加
  「第五十六次同号重打」记录，旧字节 `4eb2a8ce…cb5e92`（forge）/ `7fb2c157…1932ab`（neo）作废。
- 到此为止：**未跑回归、未验收、未 commit、未 push**（开发者明确"后续的工程不要做"）。

## 验收

- 状态：`未验收`（已部署@2026-10-13：本机 4 实例与 `patched/` 逐字节一致；未 commit、未 push）。
  - 复现步骤 1（**开透视不再发呆**）：开 `mine.seekThroughWalls`，把女仆放在**实心岩层里 / 被石头围住**
    的矿区 → 她应当**选中近矿并挖穿开路**，而不是报「附近没有值得挖的矿石」站着不动。
  - 复现步骤 2（**发呆不再吞矿**）：让她在一处反复发呆 → 看门狗重置不再把该矿永久拉黑；同一片区域里
    她应当**换一块继续挖**，而不是越挖越没目标。
  - 复现步骤 3（**真硬挡仍受约束**）：箱子/机器/基岩/岩浆仍计入预算（不会隔着一堆箱子硬挖）。
  - 判定：步骤 1/2 不再空转、步骤 3 行为不变 = 合格；异常请把 `logs/promaid.log` / `latest.log` 里
    `mine ` 相关几行贴回。
- 回归确认（不属本轮语义）：伐木逻辑**未改**；挖矿的其余自有扩展（`repick`、`MINE_REQUIRE_PICK`、
  走路卡死检测、障碍连锁、连锁耐久）**一律未动**。

## 边界（没动什么）

- 只改**选矿 / 锁定**侧；接近（approach）、挖掘公式、掉落、连锁队列均未动。
- 删 `ABANDONED` 是**对 R916 边界说明的反转**（R916 曾明确"`ABANDONED` 排除表一律未动"）——本轮按
  开发者指令"锁定逻辑套用伐木"把它对齐掉；`RECENT_DISCARD`（30 秒）+ 动态 `targetTimeoutTicks` 继续
  承担"不再回头啃够不着的矿"。
- 未改配置项与默认值；未改版本号（仍 **1.3.2**）。
- 临时脚本 `tools/_rel.py` 用后即删。

## 台账

- `DEBT.md`：新增 **BD-22**（P1，已清·实测九百一十七：挖矿选矿口径比伐木严一档 → 开透视也锁不住矿 /
  站桩发呆），并更新 §0 索引行「挖矿 / 伐木 / 农场（生产链路）」。
