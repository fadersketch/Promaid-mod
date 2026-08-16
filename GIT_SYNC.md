# Git 同步规范（两模组统一）

## 唯一事实源

- `maidmods/` 是**开发/构建/部署的唯一事实源**。
- 改代码、改版本、改资源都在 `maidmods/` 下进行。
- `patched/`、`out_*`、`staging_*`、`compile_*.txt` 是产物，不入 git。

## 两个 Git 镜像仓库

| 项目 | 镜像仓库 | 分支 | 当前版本 |
| --- | --- | --- | --- |
| Promaid | `C:\Users\Sketch\.zcode\workspace\default\promaid-mod` | `experimental/memory-port`（部署线；本地 `main` 是旧 252af 遗留，不作为部署线） | v1.5.385 |
| Heartfelt-connection | `C:\Users\Sketch\.zcode\workspace\default\heartfelt-mod` | `main` | v1.5.116 |

## 同步流程（每次改完 maidmods 后执行）

在 Windows 命令行运行：

```bat
cd /d C:\Users\Sketch\.zcode\workspace\default\maidmods
sync_to_git.bat
```

脚本会：

1. `robocopy /MIR` 把 `promaid_src` 镜像到 `promaid-mod\promaid_src`；
2. `robocopy /MIR` 把 `heartfelt_src` 镜像到 `heartfelt-mod\heartfelt_src`；
3. 同步两个仓库根目录的构建脚本/文档；
4. 在两个仓库分别 `git add -A && git commit`（没有变化时跳过）。

## 行尾规范

- 两个仓库根目录都有 `.gitattributes`：`* text=auto`、`*.bat text eol=crlf`。
- Git 内部统一保存 LF；Windows 工作树可以是 CRLF，`git status` 不会因此变脏。
- 不要手动把 CRLF 文件改成 LF 再提交，也不需要再执行 `git add --renormalize`。

## 推送

- `promaid-mod` 的 `experimental/memory-port` 是当前部署线，本地提交后需要时再 `git push origin experimental/memory-port`。
- `heartfelt-mod` 的 `main` 是当前基线，本地提交后需要时再 `git push origin main`（首次推送需先添加远程）。
- 推送需要你的 GitHub 凭据/代理，本环境不代为推送。

## 发布前检查

1. 版本号三处一致：`META-INF/mods.toml`、`build_*.py`、`build_all.bat`。
2. `patched/` 只保留最新两个 jar。
3. 重新运行 `build_all.bat`（或手动 `python build_*.py`）后，jar 内 `mods.toml` 版本正确。
4. `git status` 干净（或只有你预期的改动）。
