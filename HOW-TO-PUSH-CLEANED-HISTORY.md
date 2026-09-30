# 把清理后的历史推到远端（等一句话再执行）

**当前状态**：本地已经把 59 个历史垃圾路径清掉了，远端还是旧的。
本地 `main` 与 `origin/main` 已经分叉，所以**普通 `git push` 会被拒绝**（non-fast-forward）
—— 这是安全的失败模式，不会误伤什么。

## 为什么先没推

远端 `fadersketch/Promaid-mod` 上有一个**别人开着的 PR**：

| 项 | 值 |
|---|---|
| PR | [#26](https://github.com/fadersketch/Promaid-mod/pull/26) |
| 作者 | `rodericksthescriptkid`（**不是你**） |
| 分支 | `feat/goety-propulsion`，9 个提交 |
| 基点 | 旧 main（`53de77a1`） |

历史被重写后，这个 PR 的基点就不再是 main 的祖先，GitHub 会显示需要 rebase。
**他的提交在 fork 上，不会丢**，但对他来说是一次打断。

所以这一步值得你自己点头。要推的话，跑：

```bat
:: 1) 再确认一次备份还在、可用
git bundle verify _cleanup_backup\promaid-ALL-refs-20261001.bundle

:: 2) 强推 main（历史重写必须强推）
git push --force-with-lease origin main

:: 3) 推全部 21 个 tag（tag 也都被重写了，必须强推）
git push --force origin --tags

:: 4) 让远端把旧对象也回收掉（可选，需要仓库设置权限）
::    在 GitHub 仓库 Settings 里没有直接的 gc，一般等 ~90 天自动回收，
::    或联系 GitHub Support；本地这边已经是干净的 18.17 MiB。
```

## 如果你想先跟 PR 作者打个招呼

顺序建议：

1. 先在 PR #26 里留个话，说明 main 的历史要清理、请他 rebase；
2. 等他确认，或者先把 PR 合掉/关掉；
3. 再执行上面的强推。

## 回滚（万一后悔）

备份里有清理 **之前** 的全部 48 个 ref：

```bat
:: 另找一个目录，从备份重建一份完整旧仓库
git clone _cleanup_backup\promaid-ALL-refs-20261001.bundle restore-old
cd restore-old
:: 这时 HEAD 是旧的；把它的 main 推回去就能复原远端
git push --force origin refs/remotes/origin/main:refs/heads/main
```

## 只想要"以后不再堆垃圾"、不想重写历史？

那就**别推**，本地保持现状即可 —— 单独看这次提交
（`架构：归一化分形结构树 + 仓库根大扫除 + tools/ 归位`）已经做到：

- `.gitignore` 从"只按后缀排除文件"改成 `/_*/` 前缀规则，目录形态的草稿不再漏网；
- 14037 条未跟踪垃圾 → 0；
- 仓库根 46 个脚本归位到 `tools/`。

也就是说：**旧历史不清，垃圾也不会再长**。要不要连旧历史一起清，是你的取舍。
