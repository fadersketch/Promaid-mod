# 实测八百五十二 · 部署 + 专服回归 + release 声明补写（手册英文翻译 / 手册整体重写 / DIY 模式）

## 排班（开工前先填，硬门禁 —— `WORKFLOW.md` §1）

- **跑道**：只改文档 + 交付动作（不碰两树 `.java`、不改功能）。判定依据：本轮全部内容是
  「把已打包的产物装到本机 + 跑专服回归 + 按开发者口述补 release 声明文字」。
- **开工前读过**：`tools/deploy/deploy_all.py`（4 个部署目标 + `patched/backup_old/` 备份 +
  「有 java/javaw 在跑就拒装」的门）、`tools/test/test_server.py` 与 `test_maid_load.py`（用法与判据）、
  `WORKFLOW.md` §5（版本纪律）/§6.1（release 说明怎么写：素材来源 = 桌面 `Promaid文本\`、开发者口吻、
  不含轮次号）、`rounds/RELEASES.tsv`（1.3.1 两行的第十五次备注）、上一轮 `R842`（同一类
  「部署 + 回归 + 文案结账」轮的先例）、`R850`（本包 = 第十五次同号重打）。
  结论：无新债；`R850` 那包就是 `patched/` 里现字节，直接装即可，不需要重新打包。
- **工作队列**（逐步，做完就勾；每步写"完成判据"）：

| # | 步 | 完成判据 | 状态 |
|---|---|---|---|
| 0 | 侦察 | `deploy_all.py` 4 目标 / 回归脚本用法 / release 声明落点（母本 + 桌面成品 + 仓库定稿 + GitHub 正文）全部确认 | ☑ |
| 1 | 部署 | `deploy_all.py` 4 个目标全部 `match=True`，旧包进 `patched/backup_old/` | ☑ |
| 2 | 回归 | `test_server` + `test_maid_load` × 两加载器四件全 PASS | ☑ |
| 3 | 文案 | release 声明五处同步写到「手册英文翻译 / 手册整体重写 / DIY 模式」，桌面成品与母本逐字节一致 | ☑ |
| 4 | 台账 | `RELEASES.tsv` 与 `R850` 记录的「未部署 / 未回归」改成带日期的实况 | ☑ |
| 5 | 验收 | 开发者实测后给出结论 | ☐ |

- **谁验收 / 怎么验**：验收人 = 开发者；验法 = 进这四个实例翻手册（中文 + 切英文各过一遍，
  重点看「这是什么？」与长章是否读得下去、DIY / 默认切换是否如常），文案看桌面
  `Promaid_v1.3.1_发布声明.md`。

## 需求 / 来源

开发者当场两条指令：

> 部署，更新release声明：加入了手册的英文翻译。对于手册功能进行了整体重写。加入了DIY模式。

> 回归

即：①把当前产物装到本机 4 实例；②release 声明补上这三条；③跑专服回归（两加载器各两件）。

## 评估

- 本轮不动代码，因此回归只证明「这一包的类装载 / mixin 注入没坏」，不覆盖手册文本改动本身
  （文本要看开发者翻手册）。
- release 声明的落点（按 `R842` 已定的口径）：`store/release_notes_1.3.1.md`（平台母本）+
  桌面 `Promaid_v1.3.1_发布声明.md`（成品，与母本逐字节一致）+ `RELEASE_NOTES_v1.3.1.md`（仓库定稿）+
  `release_body.md` / `release_body_neo.md`（GitHub Release 正文，neo 版只差两处约定位置）。
- 三条里「DIY 模式」原本已在声明里（旧标题「调试 / 预设模式」），本次按手册改名统一成
  「**DIY / 默认**」并把措辞写直白。

## 改法

1. **部署**：`tools/deploy/deploy_all.py`。两个 Forge 客户端实例的文件被占用 → 脚本按设计退回提权
   通道（`Start-Process -Verb RunAs`，返回码 0）；4 个目标逐个核对 `match=True`。
2. **回归**：`tools/test/test_server.py 1201`、`test_maid_load.py 1201`、
   `test_server.py neoforge1211`、`test_maid_load.py neoforge1211`，逐个串行跑（同一加载器共用一个
   服务器目录，不并发）。
3. **release 声明**：在「一、新功能 / 🆕 本次新增」里新增「手册整体重写 + 完整英文翻译」一条，
   把「手册能配图了」补上 R849 新增的四条合成表，并把 DIY 那条改名成「DIY / 默认」；「一句话」与
   短版同步。
4. **台账回填**：`RELEASES.tsv` 两行 1.3.1 与 `R850` 记录的部署/回归状态改成实况。

### 不做

- 不升版本号（仍 1.3.1）、不重新打包（本包即第十五次重打产物）、不 commit / 不 push、不上传平台、
  不发 GitHub Release。
- 不写 `CHANGELOG.md` / `store/version_changelog.md`（§6.1：验收前一个字都不写）。

## 触及文件

- `store/release_notes_1.3.1.md`、`C:\Users\Sketch\Desktop\Promaid_v1.3.1_发布声明.md`、
  `RELEASE_NOTES_v1.3.1.md`、`release_body.md`、`release_body_neo.md`
- `rounds/RELEASES.tsv`、`rounds/R850-guide-structure-refresh.md`
- 部署目标（4 个游戏的 `mods/`）与 `patched/backup_old/`；未动任何 `.java`、i18n、`store/` 其它文件。

## 验证

- 部署：4 个目标全部落地且 `match=True` ——
  `1.21.1-NeoForge_21.1.250` 17,666,367 B、`1.20.1-Forge_47.4.10` / `龙之冒险：新征程v2.4a` /
  `mc_server_test\pack1201` 各 17,627,539 B；与 `patched/` 内同名字节一致
  （forge `67edefcd…facf72`、neo `67b72529…f335ba`）。旧包逐个备份进 `patched/backup_old/`。
- 专服回归（跑的就是 `patched/` 里这一包）：`test_server.py 1201` **PASS**（`Done (3.072s)`）·
  `test_maid_load.py 1201` **PASS**（真的 summon 了一只女仆）· `test_server.py neoforge1211`
  **PASS**（`Done (1.409s)`）· `test_maid_load.py neoforge1211` **PASS**。四件全过；
  日志里的 ERROR 行都是别的模组存量（TLM Spell 的 loot table、Curios slot type、dist cleaner），
  无一条与 promaid 有关。
- 文案一致性：`store/release_notes_1.3.1.md` 与桌面成品 sha256 相同（`ba90ee65…2210c2`）；
  `release_body_neo.md` 对 `release_body.md` 的差异仍只有约定那两处（抬头第一行 + 下载说明）。
- 护栏：`check_workflow` FAIL 0 / WARN 2、`check_preset` FAIL 0 / WARN 2（均回基线）；
  `check_changelog` / `check_comment_style` 因本轮未动对应文件而计数不变。
- 临时脚本（`tools/_rel_*`）跑完即删，`tools/` 只剩 arch / build / deploy / test / verify / voice。

## 验收（谁说了算：开发者 —— `WORKFLOW.md` §4.1）

- 状态：`未验收`。本轮文案是开发者当场指令要求写进 release 声明的（不是「未验先宣布」），
  `CHANGELOG.md` 与 `store/version_changelog.md` 一字未动，仍按 §6.1 等验收。

## 边界（没动什么）

- 未升版本号、未重新打包、未上传平台、未发 release；未动两树 `.java` 与 i18n、未动
  `store/version_changelog.md` 与 4 份 guide changelog、未动 `_tmp_pub/`。
- release 声明抬头那句范围仍写「实测七百九十五 ~ 八百四十一」共 48 轮（`R842` 结账时的口径），
  本次新加的手册内容来自 846 ~ 850 那几轮 —— 范围要不要跟着往上挪，**留待开发者定夺**。
- §6.1 要求的素材来源文件（桌面 `Promaid文本\` 该版本那一份）目前仍只有 `写作规则.md`，
  本版内容尚未落进那个目录，同样待开发者定夺。

## 台账

- 本轮无新增债、无清债（纯交付 + 文案 + 流程）。配额不受影响（`WORKFLOW.md` §8）。
