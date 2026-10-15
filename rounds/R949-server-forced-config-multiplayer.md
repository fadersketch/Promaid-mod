# R949 多人服务器可选的「配置以服务端为准」：新增默认关闭的全服配置开关

> 跑道：承接 `rounds/R948-*.md`（对话输出语言治根 + 部署）。本轮来源是**开发者对联机行为的追问**：
> 装了本模组之后，多人下各人各配一份会不会互相干扰；如果想让「全服统一口径、只有服主能改」，得怎么落。

## 排班

- 单轮：先答现行联机行为 → 定需求（默认关的新开关）→ 两树镜像实现 → 12 护栏 → 双 jar → 部署 4/4 → 台账/轮次 → 清临时。
- 谁验收：开发者（本轮**已部署**、不跑回归；`## 验收` 记 `未验收`）。

## 需求 / 来源

开发者的两段追问，最终落成的口径：

> 「每个人都会有一本手册吗？每个人都可以调控吗？会不会出现冲突的问题？理论上在多人模式下，每个人只能对自己的女仆负责。开关和数值需要 OP 权限。」
>
> 「对于必须要 OP 权限这个东西也需要有相应的开关。只在多人模式下拥有 op 权限的人才可以看到这个并打开这个开关。」
>
> 「控制所有人的配置都满足某一个值，非 op 权限的人不能改。如果他不打开，那就没事。所有的人都能改，各改各的。」

即：**默认关**的新开关 `misc.serverForcedConfig`——关着 = 现状（各人各配、互不干扰）；打开 =
**全服以服务端那一份为准**（服务端整份推给每个客户端、客户端只在内存套用；**非 OP 只读**，**OP 的改动
写回服务端并广播全服**）。这一行**只在专服 / 局域网≥2 人、且本机是 OP** 时显示。

## 诊断（现行行为：为什么不干扰，以及缺口在哪）

- 配置注册为 `ModConfig.Type.COMMON`——**客户端与服务端各自读自己那份
  `config/promaid-common.toml`**，框架层不做同步。所以现状是「各改各的」，本身**不会冲突**：
  客户端面板改的是本机那份，服务端跑的是服务端那份；女仆的实际行为在服务端结算，用的一直是服务端那份。
- 女仆的**单人操作**（喂食 / 派活 / 开关某只女仆）与**指令**本来就已按 `isOwnedBy(sender) || hasPermissions(2)`
  与 `hasPermissions(2)` 把关，别人动不了你的女仆。**缺的那块**是那块**全局配置面板**：它是纯本地 COMMON
  配置，谁都能改自己那份，且**没有任何 OP 概念**——想要「全服统一」，此前只能让服主挨个去改每台机器的 toml。

## 改法（新增一条可选链路，两树镜像）

- **新配置项 `MISC_SERVER_FORCED_CONFIG`**（`misc.serverForcedConfig`，布尔，**默认 false**）——落在
  `misc` 段（不是 voice 段），带注释与 `translation("config.promaid.misc.serverForcedConfig")`；四个 lang
  文件（两树 zh_cn / en_us）同步补键。
- **新类 `com/maidsmart/config/ConfigSyncNetworking.java`（两树）**——骨架照 `bd/MaidBdNetworking`：
  - S2C `ConfigStatePacket`：`forced` / `amOp` / `multiplayer` + **整份配置快照**（`path → 带类型的值`，
    支持 布尔 / 整数 / 小数 / 文本 / 字符串列表 五种类型）。
  - C2S `SetForcedPacket(boolean)`、`SetValuesPacket(entries)`、`ApplyPresetPacket(bool diy)`：服务端先
    校验 `hasPermissions(2)`，再应用到服务端配置 → `SPEC.save()` → **广播全服**。
  - 复用 `MaidConfigPreset.fields()`（由 `private` 改包内可见）那张 `path → ConfigValue` 反射表；
    值按**类型**过滤（不同型一律丢弃；越界数值由 Forge / NeoForge 下次加载配置时按声明区间纠正，
    1.20.1 / 21.1 的 `IntValue` 都没有公开的 `getMinValue()`，故不做运行期钳位）。
  - 客户端静态缓存：`FORCED / AM_OP / MULTIPLAYER / 服务端快照 / 本机自己那份`。锁定时把快照套进内存
    ConfigValue（显示与行为跟随服务端），**绝不 `SPEC.save()`**；开关关掉 / 退出世界时用备份还原本机自己的值。
  - Forge 在 `ProMaidMod()` 构造器注册 4 个消息；NeoForge 靠 `@EventBusSubscriber(bus=MOD)` +
    `RegisterPayloadHandlersEvent` 自注册。
- **登录 / 登出推送**：`ProMaidExtension.onPlayerLoggedIn` 加 `pushState(player)`（并广播一次，刷新
  「局域网≥2 人」判定）；新增 `onPlayerLoggedOut` 同样广播。客户端 `ClientPlayerNetworkEvent.LoggingOut`
  → `restoreOwn()`（退出世界还原本机值）。
- **面板（`PromaidConfigScreen`，两树）**：
  1. **只读闸一处**：控件创建循环里改走新助手 `addRowWidget(...)`——锁定时把 开关 / 数值 / 文本 / 枚举
     四类控件置为不可交互（SRG `f_93623_` / Neo `active`）；导航类按钮保持可用，预设按钮改由
     `presetStep` 顶部拦截。
  2. **子页写入闸**：15 个列表子页的增删回调（水白名单 / 食物黑名单 / 补给点 / 建造黑名单 / 备选 /
     可挖 / 矿表 / 障碍 / 烹饪名单）顶部加 `if (this.listWriteBlocked()) return;`——锁定时**仍可进可看**，
     只是不能增删。
  3. **新开关行**：插进「系统与杂项 → 交互与杂项」（`utilityRows`），标签走译文键；仅
     `multiplayer && amOp` 时入表，点击走 C2S。
  4. **OP 远端写**：锁定时 OP 的改动不落本机文件，改为**关闭面板时**与上次服务端快照做差量、
     发 `SetValuesPacket`（一键预设走 `ApplyPresetPacket`）；非 OP 直接重套一遍快照后关面板。
  5. **回推刷新**：收到状态包后刷新界面（同包直连 `PromaidConfigScreen.onConfigState()`）。

## 触及文件

- 两树 × `com/maidsmart/config/ConfigSyncNetworking.java`（新增）
- 两树 × `com/maidsmart/config/MaidSmartConfig.java`（新布尔 + 注释 + 译文键）
- 两树 × `com/maidsmart/config/MaidConfigPreset.java`（`fields()` 改包内可见）
- 两树 × `com/maidsmart/config/PromaidConfigScreen.java`（只读闸 / 新行 / 远端写差量 / 回推刷新 / 子页写入闸）
- 两树 × `com/maidsmart/ProMaidMod.java`（Forge 注册网络；Neo 客户端接线）、两树 × `ProMaidExtension.java`（登录推送 + 登出广播）
- 两树 × `com/maidsmart/client/PromaidClientSetup.java`（退出世界还原）
- 两树 × `assets/maid_smart/lang/{zh_cn,en_us}.json`（新键 ×2 ×2 树）
- `tools/verify/check_preset.py`（`OFF_ACK` 加 `misc.serverForcedConfig`）
- `ARCHITECTURE-SWITCHES.md`、两树 `assets/promaid/panel_coverage.txt`、`normify-promaid/`（arch 产物重生成）
- `rounds/RELEASES.tsv`（第八十八次同号重打）+ `rounds/R949-*.md`（本文件）
- **未动**：除新开关外的全部配置项；服务端对女仆操作的既有 `isOwnedBy / hasPermissions` 把关；`MaidBdNetworking`。

## 验证

- **两树编译**：`javac EXITCODE=0`。
- **护栏 12 个**：全 `FAIL 0`。中途三处红灯并修掉：
  - `check_preset` 报新布尔路径被解析成 `voice.serverForcedConfig`（**我把它误插进了 voice 段**）→ 挪回
    `misc` 段（`BUILDER.pop()` 之前），并把 `misc.serverForcedConfig` 加进 `OFF_ACK`；
  - `check_reflection` 报 Forge `getDeclaredMethod(` **7 > 基线 6**（回推刷新原本照抄 BD 的反射助手）→
    收敛为**同包直连** `PromaidConfigScreen.onConfigState()`（面板与网络类同在 `com.maidsmart.config`，不引入
    跨模块依赖），反射回到基线 6；
  - `check_workflow` 报 `ARCHITECTURE-SWITCHES.md` 过期 → 重跑 `gen_switch_inventory.py --write`。
- **打包**：forge **17620218** 字节（sha256 `bd487469…68d3cc`）；neoforge **17655612** 字节（`5b763587…8dafb5`）。
- **装机**：`deploy_all.py` → 4 个本机实例全部 **`match=True`**。
- **留痕**：`rounds/RELEASES.tsv` 两条 1.3.2 行更新 sha256 并列**第八十八次**同号重打；
  R948 旧字节 `91F64540…0F916C`（forge）/ `2F9982F9…13D458`（neo）作废。
- **未跑实机回归**（已部署，等开发者实测）——需实测确认：单机开关行不显示、行为与现状一字不差；
  专服非 OP 面板可看不可改；专服 OP 关面板后服务端落盘并全服同步；局域网只有自己时不显示。

## 验收

- 状态：**未验收**（已部署：本机 4 实例与 `patched/` 逐字节一致；未回归、未 commit、未 push）。
- **判据**：
  - 单机：面板里**看不到**这一行，其余行为与旧版一致。
  - 专用服务器（非 OP 登录）：面板里看得到服务端推下来的值，但开关 / 数值 / 文本 / 枚举**都点不动**；
    列表子页可进可看、不能增删。
  - 专用服务器（OP 登录）：面板里**看得到这一行**，打开它 → 全服生效；在面板里改任意项并「保存并返回」→
    服务端 `config/promaid-common.toml` 落盘、其他客户端下次同步到同一份值。
  - 局域网房间只有自己：**不显示**这一行；进来第二个人（本机是 OP）后出现。
  - 关掉开关 / 退出服务器：客户端恢复自己原来那份配置。

## 边界（没动什么 / 已知取舍）

- **锁定时客户端表现类**（HUD、文案、音量、客户端键位）也跟随服务端——因为套用了整份快照，这是刻意的。
- **非 OP 仍可进列表子页查看**，只是不能增删（读权限保留）。
- **OP 的改动在关闭面板时生效**（与现有「保存并返回」一致），不是逐键即时全服同步。
- **越界数值不做运行期钳位**：1.20.1 / 21.1 的 `ForgeConfigSpec.IntValue` / `ModConfigSpec.IntValue` 都没有
  公开的 `getMinValue()`，故只做**类型**过滤；越界值由框架下次加载配置时按声明区间纠正。
- **本机是 OP 的判定**走原版权限（局域网房主天然是 OP）；单机（无多人）时整条链路不显示。

## 台账

- `rounds/RELEASES.tsv`：两条 1.3.2 `workspace` 行更新 sha256 并列第八十八次同号重打。
- `DEBT.md`：不新增条目（本轮是新增可选能力，不是新债；`getMinValue` 缺失这一 1.20.1/21.1 API 事实记在本文件 `边界`）。
