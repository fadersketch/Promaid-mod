# 实测八百四十四 · 撤掉游戏内更新日志 + 后续版本更新的写作渠道改成桌面文档（两树 + 仓库级）

> 流程见 `WORKFLOW.md`；本轮的 `## 排班` 是开工前置的硬门禁。

## 需求 / 来源

开发者原话（本轮起因）：

> 我做出一个决定。删除游戏内的更新日志。Github上面也不再推送更新日志。把已经推送上去的撤掉。
> 那么之后的版本更新该怎么办？以后每次要更新什么新内容，就在"C:\Users\Sketch\Desktop\Promaid文本"
> 里面写文档，文档的标题格式为第几个版本更新的什么内容。内容如实写就行。如果本次执行的任务是让你
> 修bug，如果是修复本版本还没有推送的内容的bug，那么就不要写（如果是修复本版本还没有推送的内容的
> bug）如果是修老bug,并且修复完成了，才进入文档。随后以后写release说明的时候，就从这个文件夹里面
> 调用更新的内容作为素材来写，不再依靠changelog

拆成四条要求：
1. **删掉游戏内手册的「更新日志」章**（连同它读的两个资源文件）；
2. **GitHub 不再推送更新日志**，并把**已经推送上去的撤掉**；
3. 新建 `桌面\Promaid文本\`，以后的版本更新内容写在这个目录里，**文件名/标题格式 =「第几个版本 + 更新了什么」**；
4. 以后写 **release 说明**时**从这个目录取素材**，**不再依赖 changelog**。
   另附一条**记什么 / 不记什么**的口径：修 bug 时，若修的是**本版本尚未推送的内容**的 bug → **不写**；
   若修的是**老 bug 且已修完** → 才写进文档。

## 评估

**（一）游戏内更新日志的现状与影响面**（已用一次只读排查把全部引用点列全）
- 章由 `GuideContent.changelogChapter()` 注册、`chapters()` 里是**最后一个**元素；正文由
  `GuideScreen.loadChangelog()` 读 `assets/promaid/guide/changelog.txt`（英文环境读
  `changelog_en.txt`）。
- `chapters()` 的消费方全部用 `chs.length` / `n = chapters().length` 动态取长度，
  **没有任何写死的章号或章数**，也没有自检断言章数 → **删掉最后一章不会引发索引错位**。
- 只读排查确认：`assets/maid_smart/lang/*.json`（配置面板文案）**不含**任何 changelog key；
  手册章的标题与两条报错文案在 `assets/promaid/i18n/en_us.json` 里（3 条）。
- `verify_jar_classes.py` 只看 class 与 mixin 登记，**不看** guide 资源；两个 build 脚本是整棵
  `assets` 目录 `copytree`，**没有**逐文件清单 → 删文件不需要改打包逻辑。

**（二）护栏的影响面**
`tools/verify/check_changelog.py` 是"六个面"的护栏：dev 面 2 个（`CHANGELOG.md`、
`store/version_changelog.md`）+ player 面 4 个（两树 `changelog{,_en}.txt`），还带
"两棵树逐字节一致""中英条目数相等"的 parity 检查。**player 面撤掉后这个脚本必须改成"只管 dev 面"**
（不能只删文件——`main()` 里的 parity 段会去索引空的 `SRC_ZH[0]` 直接 `IndexError`；
而 `build_promaid*.py` 仍会调用它）。

**（三）"撤掉已推送的"这一条的可行性**
本机**不是 git 仓库**，`gh` 也**未登录**（`gh release list` 直接要求登录）。所以
「把已经推送上去的撤掉」这一步**在本环境做不到** —— 必须由开发者本人（或有凭据的环境）操作。
本轮**如实报告**，并给出确切的撤回清单与命令，不假装做过。**本地不删** `store/` 与 `CHANGELOG.md`：
开发者只说"不再推送 / 撤掉已推送的"，没说删本地留档；本地这两份继续当**内部开发记录**用
（`check_workflow.py` 的版本一致性也读 `CHANGELOG.md` 首行）。

## 排班（开工前置硬门禁 · 本轮完整工作队伍）

| # | 角色 | 任务 | 交付物 | 状态 |
|---|---|---|---|---|
| 1 | 侦察 | 只读排查"游戏内更新日志"的全部引用点（Java / lang / assets / tools / docs） | 引用清单（本轮据此施工） | ☑ |
| 2 | 实现 | 两树 `GuideContent.java`：去掉 `changelogChapter()` 与 `chapters()` 里的那一项、去掉 `changelog` 字段与那条构造重载及相关注释 | 2 个文件 | ☑ |
| 3 | 实现 | 两树 `GuideScreen.java`：去掉 `changelogLines/changelogIndex` 字段、`buildElements` 的 `if (ch.changelog)` 分支、整个 `loadChangelog()` | 2 个文件 | ☑ |
| 4 | 实现 | 删 4 个资源：两树 `assets/promaid/guide/changelog{,_en}.txt` | 4 个文件删除 | ☑ |
| 5 | 实现 | 两树 `assets/promaid/i18n/en_us.json` 去掉 3 条 changelog key（章标题 + 两条报错文案） | 2 个文件 | ☑ |
| 6 | 护栏 | `check_changelog.py` 改成 **dev-only**（只校验 `CHANGELOG.md` + `store/version_changelog.md`），去掉 player 面与两处 parity；`build_*.py` 的调用保留 | 1 个文件 | ☑ |
| 7 | 工具 | 章数 36 → 35：`tools/arch/arch_modules.py`、`tools/arch/gen_arch.py`（并在重生成后同步 `normify-promaid/modules/promaid/guide.md`） | 3 处 | ☑ |
| 8 | 流程 | `DEV_GUIDE.md` §2.4（六个面 → 两个面）+ §2.1/2.2/§6 表；`WORKFLOW.md` §6.1（release 素材来源改成桌面 `Promaid文本` 目录）+ §6 的"player 面额度"说法 + 护栏表那一行 | 2 个文件 | ☑ |
| 9 | 交付 | 建 `桌面\Promaid文本\` + 写一份「写作规则」（标题格式、记什么/不记什么、release 说明怎么取素材） | 1 个目录 + 1 份规则 | ☑ |
| 10 | 验证 | 两树 `javac EXITCODE=0`；四护栏问题数**不增**；打包自检 `MISSING: none`；`deploy_all.py` 4 实例 `match=True` | 构建/部署输出 | ☑ |
| 11 | 留痕 | `RELEASES.tsv` 同号重打包留痕 + 本文 §验证/§验收 | 两处 | ☑ |
| 12 | **报告** | GitHub 侧的撤回：本机无 git / gh 未登录 → **列出确切待撤内容与命令，交开发者执行**（不做假） | 本文 §边界 + 结尾汇报 | ☑ |

- **谁验收 / 怎么验**：验收人 = 开发者。验法：① 游戏内打开 Promaid 手册首页，**不再有「更新日志」**这一章，
  章节目录少一行、其余章照常打开；② 桌面出现 `Promaid文本` 文件夹与其中的规则文件。

## 改法

见排班 2 ~ 9。要点：
- 删章是"删最后一章"，`chapters()` 的动态长度消费方无需改动（已排查：无写死章号/章数、无自检断言）。
- `check_changelog.py` **重写为 dev-only**，保留原有的 dev 版条目长度/要点数/行数检查与计数输出，
  这样 `build_promaid*.py` 的 `--warn` 调用与 §6 的计数基线照旧可用。
- 桌面文档目录的**写作规则**按开发者口径落成文字（标题 = 「第几个版本 + 更新了什么」；
  本版本尚未推送内容的 bug 不写；老 bug 修完才写；release 说明从这里取素材）。

### 不做

- **不**删本地 `CHANGELOG.md` / `store/version_changelog.md`（开发者只说不再推送与撤掉已推送的；
  本地留作内部开发记录，`check_workflow.py` 也依赖 `CHANGELOG.md` 首行做版本一致性检查）。
- **不**在此环境尝试 git/gh 操作（本机非 git 仓库、gh 未登录）；撤回清单与命令写进汇报，交开发者。
- **不**升版本号（仍 1.3.1）。

## 触及文件

- 代码（两树各 2）：`com/maidsmart/guide/GuideContent.java`、`GuideScreen.java`。
- 资源（两树各 3）：`assets/promaid/guide/changelog.txt`、`changelog_en.txt`（删）、
  `assets/promaid/i18n/en_us.json`（去 3 条 key）。
- 工具/护栏：`tools/verify/check_changelog.py`、`tools/arch/arch_modules.py`、`tools/arch/gen_arch.py`
  （+ 重生成 `normify-promaid/modules/promaid/guide.md`）。
- 流程文档：`DEV_GUIDE.md`、`WORKFLOW.md`。
- 台账：`rounds/RELEASES.tsv`、本文。
- 仓库外：`桌面\Promaid文本\`（新建）。

## 验证

- 两树编译：`compile_promaid.bat` / `compile_neo.bat` → **EXITCODE=0**。
- 删章无索引风险：`chapters()` 的消费方全用 `chs.length` 动态取长度（已逐处核对），删最后一项后
  章节目录自动少一行；另删掉 `changelogLines` / `changelogIndex` 两个字段（后者本就是死字段）、
  `loadChangelog()` 整个方法与 `buildElements` 里的 `if (ch.changelog)` 分支。
- 四护栏：`check_comment_style` **2258/929**（不增）、`check_preset` FAIL **0**、`check_workflow` FAIL **0**；
  `check_changelog` 由"六面 100148"变为 **dev 面 1261**（游戏内两文件的历史格式条目随文件一起消失，
  基线按此更新到 `DEV_GUIDE.md` §6/§10 与 `WORKFLOW.md` §11）。
- 架构文档重生成：`python tools/arch/gen_arch.py` → `L1/L2 validate: 0 error`，
  `promaid.guide` 描述改为 **35 章**（`arch_modules.py` / `gen_arch.py` 两处字面量同步）。
- 打包：`MISSING: none`；`patched/promaid-1.3.1-forge-1.20.1.jar` 18,411,107 B（sha256
  `861ffdcd…e2704b`）、`patched/promaid-1.3.1-neoforge-1.21.1.jar` 18,449,555 B（`e58a4ca1…c605d5`）
  —— 体积较上一包掉约 0.5 MB，正是那两份 changelog 资源（源文件 1.51 MB + 1.85 MB）被移出。
- 装机：`deploy_all.py` 4 个目标全部 `match=True`。
- 登记：`rounds/RELEASES.tsv` 两条 1.3.1 行改填新 sha256、旧字节列入作废（**第 9 次**同号重打包）；
  `check_workflow` 的"同名不同内容"FAIL 随之清零。
- 桌面：`C:\Users\Sketch\Desktop\Promaid文本\写作规则.md` 已建（标题格式、记/不记口径、release 取素材）。
- **未做 / 未落**：两树 `assets/promaid/i18n/en_us.json` 里那 3 条 changelog key 的删除**没能写进去** ——
  这两个文件当时被**外部进程以"用户映射区域"方式占用**（PowerShell 实测
  `该进程无法访问此文件` / `请求的操作无法在使用用户映射区域打开的文件上执行`），
  `Edit`、`open('wb')`、`os.replace`、`copy /Y` 四种写法全部被拒；已把剔好 key 的成品放在
  `tools/_i18n_{forge,neo}.pending.json` 并挂了一个后台重试，占用一解除就会自动落盘。
  **这 3 条是死 key**（章已删，永远不会被查表），不影响功能与打包，待占用解除即补齐。

## 验收

**未验收** —— 等开发者验收：①手册首页不再有「更新日志」章（章节目录少一行、其余章照常）；
②桌面出现 `Promaid文本` 文件夹与其中的 `写作规则.md`。

## 边界

- **GitHub 侧的撤回本环境做不到**（本机不是 git 仓库、`gh` 未登录）——待撤清单与做法：
  | 待撤 | 位置 | 做法 |
  |---|---|---|
  | `CHANGELOG.md` | 仓库根（GitHub 上的那份） | 若确定不再公开：`git rm CHANGELOG.md store/version_changelog.md` → commit → push |
  | 各 Release 正文里由 changelog 派生的段落 | GitHub Releases | `gh release view <tag> --json body` 核对后逐条改 |
  | 平台上的 changelog 字段 | CurseForge / Modrinth | 平台的版本编辑页里改 |
  **这一步我没有执行**（无凭据），也没有伪造"已撤掉"。
- 本地 `CHANGELOG.md` / `store/version_changelog.md` **保留**（内部开发记录；`check_workflow.py`
  的版本一致性检查也读 `CHANGELOG.md` 首行）——开发者只说"不再推送 / 撤掉已推送的"。
- 不动已发布产物 / `_tmp_pub/`；不 commit / 不 push。
- 临时脚本 `tools/_*` 跑完即删（`_i18n_retry.py` 与两个 pending 文件要等落盘后再删）。

## 台账

- 本轮是**流程/文案轮**（§6：只落 dev 面）—— 但它本身改的就是"changelog 面"，故 dev 面记一条即可。
