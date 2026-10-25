# R1079 整合包适配：手册/配置拆分、发书开关、蓝图禁用与删除持久化

## 排班

- **跑道**：整合包适配（手册/配置拆分 + 发书开关 + 蓝图禁用）。
- **工作队列**：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 1 | 手册拆分 | 手册首页可隐藏配置 / DIY 入口，指南照常 | ★ |
| 2 | 发书开关 | 首次进存档可配置为不发书 | ★ |
| 3 | 蓝图禁用 | 配置按 id/文件名隐藏内置/外部蓝图，建造也拒绝 | ★ |
| 4 | 删除持久化 | 手动删掉的内置蓝图不再每次启动补回 | ★ |
| 5 | 两树编译、14 护栏、四套测试、4 实例部署 | 全部 PASS / match=True | ★ |
| 6 | 交整合包作者验收 | 默认 config 打包后生存玩家看不到配置页 | ☐ |

## 反馈原文（整合包作者）

> 我这里正在考虑把 promaid 塞进我的整合包，虽然我大概能研究出来怎么通过 DIY 模式调整配置，
> 但是这里我在考虑的是，在整合包环境下有些功能我是不希望开放的，出于平衡性等考虑。
> 能不能把开局的手册里面，指南的部分和配置的部分拆开来，然后进入存档的时候发书与否可以开关？
> 这样我希望生存模式的玩家就不用去接触配置部分，我会直接 DIY 好作为默认的 config 打包给整合包。
> 然后还有就是蓝图，有些蓝图可能不符合整合包主题，这里我想删掉，但是每次进入存档它又会重新把
> 蓝图都加载回来，我不知道该怎么办。

## 根因 / 现状

### 1. 手册与配置混在一起

`BlueprintBookScreen.homeViewButtons()` 在手册首页固定放 6 个入口：详细介绍、建造、女仆管理、
模组详细配置、女仆记忆、DIY / 默认模式。后两个直接改全模组配置，生存玩家一进手册就能看到。

### 2. 发书没有开关

`FirstJoinGift.onLogin` 首次进存档时通过进度奖励发《Promaid 手册》与《排班表》，
没有任何配置项可以让整合包作者关掉。

### 3. 删掉的内置蓝图会被补回

`BlueprintFileIo.ensureBundledBlueprints()` 每次启动遍历 `BUNDLED_BLUEPRINT_FILES`，
文件不存在就重新从 jar 解压——玩家删了也白删。内置代码生成的 19 个蓝图（`BUILT_IN_NAMES`）
则根本没有删除入口。

## 改法（两树）

### A. 手册 / 配置拆分（`misc.bookConfigAccess`，默认开）

- 关闭后，手册首页只保留「详细介绍 / 建造 / 女仆管理 / 女仆记忆」。
- 「模组详细配置」与「DIY / 默认模式」两个入口整行不出现；行号动态排列，不留空洞。
- 配置本身仍可从模组列表的 Config 进入（只影响手册入口，不锁配置系统）。

### B. 进存档发书开关（`misc.giveStarterBooks`，默认开）

- `FirstJoinGift.onLogin` 最前面读配置；关闭时直接返回——既不授予进度、也不发书。
- 只影响**首次发放**：已经领过的存档（进度已完成）本来就不会重复发。
- 关闭期间进入的玩家不会被记账；以后重新打开，他们会按"首次"收到一次。

### C. 蓝图禁用（`build.disabledBlueprints`，默认空列表）

- 新配置项：按 id 或文件名填写，逗号分隔、大小写不敏感，可带
  `.nbt / .snbt / .litematic / .schem / .json` 后缀；外部蓝图的
  `maid_smart_ext:` 前缀可省略。
- `BlueprintStepMath.isDisabled()` 统一判据；`getBlueprint()` 命中直接返回 null，
  因此目录、建造、AI 下单三条路都拿不到。
- `BlueprintCatalog.buildCatalogEntries()` 跳过禁用的外部/内置蓝图；
  `BlueprintFileIo.scanExternalBlueprints()` 不注册被禁用的文件。
- 面板「建造」页新增一行「禁用的蓝图」，可直接编辑列表。
- 适合整合包作者在默认 config 里写死，不需要删 jar 里的资源。

### D. 删除内置蓝图后不再补回（`.bundled-manifest`）

- `ensureBundledBlueprints()` 现在维护
  `config/maid_smart/blueprints/.bundled-manifest`：解压成功的内置文件名写进清单。
- 下次启动：文件存在 → 跳过；文件不存在但**清单里有** → 判定为主动删除，不再补回；
  文件不存在且清单里没有（首次安装/升级新增）→ 正常解压。
- 想恢复全部内置蓝图：删掉 `.bundled-manifest`，下次启动会把缺的重新解压。
- 注意：本版第一次启动时，之前已删除但尚未进清单的文件会补回一次；再删一次即永久生效。

## 触及文件

- `promaid_src/com/maidsmart/config/MaidSmartConfig.java`（3 个新配置项）
- `promaid_src_neo/...` 同名镜像
- `promaid_src/com/maidsmart/config/PromaidConfigScreen.java`（建造页 + 杂项页新行）
- `promaid_src_neo/...` 同名镜像
- `promaid_src/com/maidsmart/config/MaidConfigPreset.java`（两个界面/分发开关 DIY 保留）
- `promaid_src_neo/...` 同名镜像
- `promaid_src/com/maidsmart/FirstJoinGift.java`（发书开关）
- `promaid_src_neo/...` 同名镜像
- `promaid_src/com/maidsmart/build/BlueprintBookScreen.java`（手册入口拆分）
- `promaid_src_neo/...` 同名镜像
- `promaid_src/com/maidsmart/build/BlueprintStepMath.java`（isDisabled + 查找闸）
- `promaid_src_neo/...` 同名镜像
- `promaid_src/com/maidsmart/build/BlueprintCatalog.java`（目录过滤）
- `promaid_src_neo/...` 同名镜像
- `promaid_src/com/maidsmart/build/BlueprintFileIo.java`（扫描过滤 + manifest）
- `promaid_src_neo/...` 同名镜像
- 4 个 lang 文件新增 3 个译文键；`panel_coverage.txt` / `ARCHITECTURE-SWITCHES.md` 重新生成

## 验证

- 两树编译 `EXITCODE=0`。
- 14 护栏 `FAIL 0`。
- `test_server 1201` / `test_server neoforge1211`：PASS。
- `test_maid_load 1201` / `test_maid_load neoforge1211`：PASS。
- `deploy_all.py`：4 个实例 `match=True`。
- 产物与哈希见本轮 `RELEASES.tsv`。

## 验收

- 关 `giveStarterBooks` 后新存档首次进入：背包里没有《Promaid 手册》/《排班表》，日志无「首次进入，已发放」。
- 关 `bookConfigAccess` 后打开手册：首页只有详细介绍/建造/女仆管理/女仆记忆；
  看不到「模组详细配置」「DIY / 默认模式」，且按钮之间无空洞。
- `disabledBlueprints` 写入 `mega_castle`（或 `mega_castle.snbt`）后开手册：
  「建造」目录不再出现巨型城堡；点击/AI 下单同样拿不到该蓝图。
- 删掉 `config/maid_smart/blueprints/` 里某个内置文件并重启：日志不再出现
  `ensureBundledBlueprints: 已内置蓝图 ...`，文件也不会被补回；删掉 `.bundled-manifest`
  后重启则重新解压。
- 默认配置（两个开关都开）下，现有玩家行为与升级前一致。

## 整合包作者使用说明

`config/promaid-common.toml`：

```toml
[build]
    disabledBlueprints = ["mega_castle", "skyscraper.snbt", "tabby_cat_statue"]

[misc]
    giveStarterBooks = false
    bookConfigAccess = false
```

- `disabledBlueprints` 同时作用于内置代码蓝图和 `config/maid_smart/blueprints/` 里的文件；
  命中的蓝图在手册里看不到，也无法被建造或 AI 下单。
- 也可以继续手动删文件：删掉一次后 `.bundled-manifest` 会记住，不再补回。
- 想恢复：清空 `disabledBlueprints`；或删掉 `.bundled-manifest` 让内置文件重新解压。

## 边界 / 已知取舍

- `bookConfigAccess` 只隐藏**手册入口**；模组列表的 Config 仍可进（这是客户端级入口，
  不锁死配置系统）。如果整合包需要彻底只读，可配合 `misc.serverForcedConfig`（多人）。
- `giveStarterBooks` 只管首次发书，不做"每次进存档都发"；已有存档不会重复发。
- 删除持久化只覆盖 jar 内置文件；玩家自己导入的蓝图删除后本来就不会回来。
- 本版第一次启动会把"更新前已删、但还没进清单"的文件补回一次，这是清单首次建立的代价。

## 台账

- 未新增债。反射计数保持基线（`getMethod` Forge 205 / Neo 206）。
