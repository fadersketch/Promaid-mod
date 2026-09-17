# Git 同步规范（两模组统一）

> 2026-09-17 修正：本文件此前描述的方向已经失效，造成过一次错位，本次实测后重写。

## 方向：谁是事实源

**当前事实源 = 两个镜像仓库**（`promaid-mod` / `heartfelt-mod`），不是 `maidmods/`。

历史设计是 `maidmods/` 为源、`promaid-mod` 为镜像，靠 `sync_to_git.bat` 用
`robocopy /MIR` 单向覆盖。但实际开发早已在 `promaid-mod` 里直接进行，`maidmods/`
停在旧版本，两边越拉越远，直到 2026-09-17 核对时：

| 树 | `promaid-mod` | `maidmods`（修正前） |
| --- | --- | --- |
| `promaid_src` | v1.2.0，467 文件 | v1.1.0，247 文件（停在 2026-08-24） |
| `promaid_src_neo` | 470 文件 | **不存在** |

当时若直接跑 `sync_to_git.bat`，`robocopy /MIR` 会**删除** `promaid-mod` 侧
**227 个文件**（95 个 Java 源码 + 122 个语音 .ogg + 8 个 json + 2 个 png），
含整套空袭/飞行作战、激流突进、碑石建造与 `MaidPlaceGuard`。

**已于 2026-09-17 反向同步完毕**：以 `promaid-mod` 为准补齐 `maidmods`，
三棵树现已逐文件零差异（`promaid_src` 467=467、`promaid_src_neo` 470=470、
`heartfelt_src` 106=106），且 `maidmods` 侧两树均可独立编译（javac 零错误）。

## 两个 Git 镜像仓库

| 项目 | 镜像仓库 | 分支 | 当前版本 |
| --- | --- | --- | --- |
| Promaid | `C:\Users\Sketch\.zcode\workspace\default\promaid-mod` | `main`（当前线） | v1.2.0 |
| Heartfelt-connection | `C:\Users\Sketch\.zcode\workspace\default\heartfelt-mod` | `main` | v1.0.2 |

- `promaid-mod` 另有分支 `experimental/memory-port`，**停在 v1.1.0、不含 `promaid_src_neo`**，
  与 `main` 已分叉（不可快进）。它不是当前部署线——要发布就用 `main`。
- `origin/HEAD` 指向 `origin/main`。

## 同步流程：改完之后

### 1. 备份（`maidmods` 不是 git 仓库，删了没法回滚）

往 `maidmods` 方向同步前**必须先快照**：

```bat
robocopy "maidmods\promaid_src" "maidmods\_backup_promaid_src_<日期>" /MIR /NFL /NDL /NJH /NJS
```

### 2. 先预演，再执行

`/MIR` 是**带删除的镜像**（目标侧多出来的文件会被删掉）。执行前一律先加 `/L` 只列不做，
确认“多余文件”列表里没有仍需要的东西：

```bat
robocopy "promaid-mod\promaid_src"     "maidmods\promaid_src"     /MIR /L /NFL /NDL /NJH /NP
robocopy "promaid-mod\promaid_src_neo" "maidmods\promaid_src_neo" /MIR /L /NFL /NDL /NJH /NP
```

### 3. 执行同步（三份都要同步）

缺一份就会再次错位：

```bat
robocopy "promaid-mod\promaid_src"     "maidmods\promaid_src"     /MIR /NFL /NDL /NJH /NP
robocopy "promaid-mod\promaid_src_neo" "maidmods\promaid_src_neo" /MIR /NFL /NDL /NJH /NP
:: 构建脚本与文档：build_promaid*.py compile_promaid.bat gen_compile*.py run_javac_neo.py
::                  deploy*.py test_server.py verify_jar_classes.py CHANGELOG.md README.md 等
```

> `sync_to_git.bat` 目前**只覆盖 `promaid_src` 与四个构建脚本，不含 `promaid_src_neo`**，
> 且默认方向是 `maidmods -> promaid-mod`。方向反了或漏了 neo，都会造成数据丢失。

### 4. 一致性核对（必须做）

按文件内容比对，不要只看文件数：逐文件 md5 比对三棵树，
判据是 `only in A` / `only in B` / `content DIFFERENT` **三项全为 0**。

### 5. 验证 `maidmods` 侧可编译

```bat
cd /d C:\Users\Sketch\.zcode\workspace\default\maidmods
python gen_compile.py
call compile_promaid.bat          rem 期望 EXITCODE=0 / 0 个错误
python gen_compile_neo.py
python run_javac_neo.py           rem 期望 exit: 0 / 16 个警告
```

## 行尾规范

- 两个仓库根目录都有 `.gitattributes`：`* text=auto`、`*.bat text eol=crlf`。
- Git 内部统一保存 LF；Windows 工作树可以是 CRLF，`git status` 不会因此变脏。
- 不要手动把 CRLF 文件改成 LF 再提交，也不需要再执行 `git add --renormalize`。

## 推送

- `promaid-mod` 的 `main` 是当前线，本地提交后需要时再 `git push origin main`。
- `heartfelt-mod` 的 `main` 是当前基线，本地提交后需要时再 `git push origin main`。
- 推送需要你的 GitHub 凭据/代理，本环境不代为推送。

## 发布前检查

1. 版本号三处一致：`META-INF/mods.toml`、`build_*.py`、`build_all.bat`。
2. `patched/` 只保留最新两个 jar。
3. 重新运行构建（或手动 `python build_*.py`）后，jar 内 `mods.toml` 版本正确。
4. `git status` 干净（或只有你预期的改动）。

## 附：`maidmods/` 里已知的历史包袱

- `maidmods/build_all.bat` 版本号停在 `promaid-1.0.3` / `heartfelt_connection-1.0.0`，
  且只走单树（不含 neo）。它是历史文件，**不要**用它做发布构建；用
  `promaid-mod` 的 `build_promaid.py` / `build_promaid_neo.py`。
- `maidmods/repo-sync/` 是同一个 GitHub 仓库的**嵌套旧克隆**（停在 2026-08-13 的 v1.0.1），
  不是同步机制，别把它当第二个远端。
- `maidmods/promaid_src` 修正前那 7 个文件（`MaidDimensionFollow`、
  `RelationshipMemoryAdapter`、`CallResponseChatSpamMixin`、`HomingPotionMixin`、
  `LoveLoatheHungerGateMixin`、`MaidDebugPanelMixin`、`MixinInteractionSittingAllow`）
  均已被 v1.2.0 **有意移除或合并**（见 `CHANGELOG.md`），
  快照留在 `maidmods/_backup_promaid_src_v1.1.0_20260917/`。
