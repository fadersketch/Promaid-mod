# 实测一千〇一十一 取装备误判补发 + 指标石缺料语音 + 女仆跨维度失踪（1.20.1 实机反馈）

> ⚠ **更正（实测一千〇一十二）**：本文第 1 条「装备其实穿在玩家身上、补包是误判」的结论**已被开发者实机否决**。
> 开发者确认：从女仆装备槽/主副手拖到玩家物品栏，**放下那一下物品真的消失**，补发不是误判。
> 本条的定位与修法（`locateEquip` 扫玩家 41 格）只对「托管误判导致重复补发」这一分支有效，
> **不是**"消失"的根因；真因由 `R1012-equip-probe.md` 的实机探针重新定案：
> 单机本地连接不序列化 + 装备包不复制 → 传送后客户端与服务端共享同一枚 ItemStack，客户端放下预判把它清零。
> 长期修复 `EquipmentPacketCopyMixin` 已在 R1013 随实机验收通过并保留，诊断针已拆除。
> 第 2、3 条（指标石语音、女仆跨维度失踪）不受影响，仍然有效。

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）
- **跑道**：A 修 bug。三条都是玩家能感知的行为错误（装备被补成两份、缺语音、女仆从集合里消失），不是新功能；指标石黑名单一条是口径确认，零行为改动。
- **开工前读过**：`R1003`（1.20.1 潜行 SRG 映射，当前 1.3.3 第 143 次重打）、`R1002`（收符事务化 / 第 142 次）、`ARCHITECTURE-SWITCHES.md` 的搭建与指标石条目、`DEBT.md`（无等价条目）、`rounds/RELEASES.tsv`。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 定位装备"消失" | 存档/玩家 NBT 证明装备还在玩家身上，补包是误判 | ☑ |
| 1 | 定位女仆失踪 | 区域文件找到实体；`MaidWorldData` 最后位置与 LAST_SEEN 不一致 | ☑ |
| 2 | 定位缺语音 | 气泡文本已有；manifest 只有旧文案的 exact 条目，新文案匹配不到 | ☑ |
| 3 | 两树修 + 语音生成 | `EXITCODE=0`；新 ogg 进两树；manifest 命中 | ☑ |
| 4 | 编译 + 14 护栏 | 两树 `EXITCODE=0`；`FAIL 0 / WARN 0` | ☑ |
| 5 | 重打 + 装机 + 回归 | 双 jar；4 实例 `match=True`；专服回归三件 PASS | ☑ |
| 6 | 交开发者验收 | 1.20.1 实机：取装备不再补发；指标石缺料有语音；『一键集合』能找回下界的女仆 | ☐ |

## 需求 / 来源
- 开发者 1.20.1 实机反馈三条：
  1. 从女仆装备槽/主副手取装备后"直接消失"，约 1 秒后补包又发到女仆脚下；要求定位根因，补包只能兜底。
  2. 指标石缺搭建方块时只有文字气泡、没有语音；并问"只允许搭建的方块是否也遵从搭路黑名单"。
  3. 游戏过程中女仆突然彻底消失。
- 语音补充说明：缺的只有语音，气泡文本不缺乏；用桌面 `API.bat` 的本地 GPT-SoVITS 生成。

## 评估

### 1. 装备"消失"（P0：可复制物品，实际不是丢失）
- **证据**：解析当前存档 `playerdata`，钻石护甲在下标 100–103、下界合金剑在下标 7——都在玩家自己的护甲/快捷栏里；女仆 NBT 里她原本那套也还在。也就是说东西没有丢，是托管逻辑"看不见玩家身上的格"导致的**误判 + 补发**。
- **根因**：`locateEquip` 只扫 `containerMenu` 的槽位。女仆界面 `MaidMainContainer` 的玩家段只加了主背包 + 快捷栏 36 格，**不含玩家自己的护甲/副手**（那些在玩家 `InventoryMenu` 里）。玩家把取下的装备穿到身上后，菜单里查不到 → 1.5 秒后判"四处查无" → `putBack` 往女仆背包塞一件；背包满就掉她脚下。玩家看到的"消失 + 脚下补包"就是这一条。
- **同类点**：两树同函数都只扫菜单；Neo 侧同样缺玩家护甲/副手。已一并修。
- **修法**：在两树 `locateEquip` 里加玩家完整 41 格扫描（0..35 主背包、36..39 护甲、40 副手），命中即 `LOC_PLAYER`。托管表逻辑不动，补包仍是"真查无"时的最后兜底。

### 2. 指标石缺料语音 + 黑名单口径
- **根因**：`IndexStoneBuildBehavior` 的气泡文本已在（`没有可以搭建的方块了…主人给我一些方块吧（搭完才会走）`），但 `voice/manifest.json` 里只有旧文案 `没有可以搭建的方块了…再找不到材料我就先停啦` 的 `exact` 条目，新文案匹配不到任何语音。
- **修法**：新增 `index_no_blocks.ogg` 的 `contains` 条目（`没有可以搭建的方块了…主人给我一些方块吧`），保留旧条目；用 GPT-SoVITS 生成日语语音，峰值归一化后编码为 ogg（70,133 字节），两树各装一份。
- **黑名单口径**：是，指标石与搭路共用同一套 `MaidBuildBlockFilter`。取料走 `takeBuildBlock(...)`，背包筛选走 `isUsableBuildStack(...)`，因此安全黑名单（坠落/TNT/床/门/双格植物/伤害/危险方块）、玩家 `bridge.buildWhitelist` / `buildBlacklist`、`bridge.buildOnlyNatural` 默认口径全部生效。代码注释已从"四个消费方/四个链路"改为"五个消费方/五个链路（自保搭高/挖矿/伐木/搭路/指标石）"。

### 3. 女仆"彻底消失"（P1：不是删除，是跨维度留在下界）
- **证据**：`DIM-1/entities/r.0.0.mca` 的 chunk 25,14 能找到她的完整实体 NBT；`touhou_little_maid_world_data.dat` 记录 UUID `3a4f0d1a-6741-4639-8bfb-fafcfbe3a7fa`、坐标 `(412,104,236)`、维度 `the_nether`、时间戳 16:50:28。她还在存档里。
- **根因**：主人被命令瞬移离开下界时，她可能正在自保/停在原地；区块卸载后 `LAST_SEEN` 快照仍指着旧位置或旧维度。排班手册的『一键集合』按过期快照强载了错误区块，15 秒超时后把登记项删掉——玩家侧表现就是"彻底消失"。
- **修法**：
  1. `summonAll` 增加 TLM `MaidWorldData` 第二来源：同一只女仆按 `entityId` 取最新记录，位置不同时修正 `LAST_SEEN`（保留原 `stayPut`/`homeOnly` 标记）。记录上限从 5 分钟放宽到 1 天，保证这次 23 分钟前留下的她也能被『一键集合』找回；更久远的记录仍只当历史，避免每次集合都翻旧账。
  2. 两树 `onMaidJoin` 末尾立即调用一次 `MaidChunkLoadManager.tick(server)`，让刚入世界/刚跨维度的女仆当场刷新登记并挂上强载票，堵住"入世界后 5 秒空窗"。
- **恢复方式**：装新包后打开排班手册点『一键集合』（记录在 1 天内），或直接去下界 `(412,104,236)` 附近让她入载。

## 改法
- 装备：两树 `locateEquip` 增加玩家完整 41 格扫描。
- 女仆：两树 `summonAll` 增加 `MaidWorldData` 回退；`onMaidJoin` 增加立即 `tick`；回退记录时效 1 天。
- 语音：两树 `manifest.json` 增加 `index_no_blocks` 条目；新增 `index_no_blocks.ogg`；`gen_voice_pack.py` 增加风格与日文台词。
- 注释：`MaidBuildBlockFilter` / `MaidSmartConfig` / `en_us.json` 的"四个消费方/四个链路"统一改为"五个"，把指标石写进清单。
- 被否掉的方案：把补包直接删掉——真丢时仍需兜底，所以保留，只修"误判"；给女仆界面补玩家护甲槽——影响 TLM 原生 UI，改动面大，且在 `locateEquip` 里扫描更稳。

## 触及文件
- `promaid_src/com/maidsmart/ProMaidExtension.java` / `promaid_src_neo/...`：`locateEquip` 全背包扫描；`onMaidJoin` 立即 `tick`。
- `promaid_src/com/maidsmart/follow/MaidChunkLoadManager.java` / `promaid_src_neo/...`：`MaidWorldData` 回退 + 1 天时效。
- `promaid_src/com/maidsmart/tool/MaidBuildBlockFilter.java` / `promaid_src_neo/...`：五链路注释。
- `promaid_src/com/maidsmart/config/MaidSmartConfig.java` / `promaid_src_neo/...`：五链路注释。
- `promaid_src/assets/promaid/voice/manifest.json` / `promaid_src_neo/...`：`index_no_blocks` 条目。
- `promaid_src/assets/promaid/voice/index_no_blocks.ogg` / `promaid_src_neo/...`：新语音。
- `promaid_src/assets/promaid/i18n/en_us.json` / `promaid_src_neo/...`：五链路英文说明。
- `tools/voice/gen_voice_pack.py`：`plead` 风格与日文台词。
- 无新配置项、无新 mixin、无网络协议变化。

## 验证
- 两树编译：`compile_promaid.bat` / `compile_neo.bat` 均 `EXITCODE=0`。
- 14 护栏：全部 `FAIL 0 / WARN 0`（`check_mixin_targets` 267 / 133 注入点）。
- 双 jar（1.3.3 第 144 次同号重打）：
  - forge `00fac101…fee8`（18,009,400 字节）
  - neoforge `be3abc77…cb64`（18,057,000 字节）
- `deploy_all.py` 4 个目标全部 `match=True`。
- 专服回归：`test_server.py 1201` PASS、`test_server.py neoforge1211` PASS、`test_maid_load.py neoforge1211` PASS。
- 语音：`gen_voice_pack.py` 生成 554,284 字节原始 wav，`norm_voice_pack.py` `maxv=-9.20 gain=+7.70`，ogg 70,133 字节；`install_voice_pack.py` 两树 `entries=180 copied=1 missing=none`。
- **反证**：装备侧旧逻辑只扫 `containerMenu`，而 `MaidMainContainer` 玩家段仅 36 格，玩家护甲/副手格物理上不可能被扫到；新逻辑扫 `Inventory.getContainerSize()` 的 41 格，能命中存档里下标 100–103/7 的那几件。女仆侧旧逻辑在 `LAST_SEEN` 过期后无第二来源；新逻辑从 `MaidWorldData` 取到 `(412,104,236)/the_nether` 并强载 chunk 25,14。
- 未做：未在 1.20.1 客户端实机复验（等开发者验收）；未改指标石取料/搭路的业务判定，只补语音与注释。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）
- 状态：`未验收`。
- 未验收之前：不写 changelog、不写 release 说明、不发平台。

## 边界（没动什么）
- 装备托管表、1.5 秒观察窗、`putBack` 的落点规则一字未改；只把"查无"的判定补全。
- 指标石的安全黑名单/白名单/天然方块口径未改；只补语音与注释。
- 女仆集合的 home/坐姿/骑乘豁免、落点直达、15 秒超时撤票逻辑未改；只增加第二位置来源与入世界刷新。
- 1.20.1 与 1.21.1 两树镜像；没有新增配置项或协议字段。

## 台账
- 清掉的债：无既有 `BD-` 条目；这三条都是首次闭环。
- 新增的债：无。
- 配额：A 类修 bug，不触发功能轮配额。
